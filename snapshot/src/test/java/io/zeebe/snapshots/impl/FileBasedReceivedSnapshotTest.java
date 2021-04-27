/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.snapshots.impl;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.zeebe.snapshots.ConstructableSnapshotStore;
import io.zeebe.snapshots.PersistedSnapshot;
import io.zeebe.snapshots.PersistedSnapshotListener;
import io.zeebe.snapshots.ReceivableSnapshotStore;
import io.zeebe.snapshots.ReceivedSnapshot;
import io.zeebe.util.FileUtil;
import io.zeebe.util.sched.testing.ActorSchedulerRule;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileBasedReceivedSnapshotTest {

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();
  @Rule public ActorSchedulerRule senderScheduler = new ActorSchedulerRule();
  @Rule public ActorSchedulerRule receiverScheduler = new ActorSchedulerRule();

  private ConstructableSnapshotStore senderSnapshotStore;
  private ReceivableSnapshotStore receiverSnapshotStore;
  private Path receiverSnapshotsDir;
  private Path receiverPendingSnapshotsDir;

  @Before
  public void before() throws Exception {
    final int partitiondId = 1;
    final File senderRoot = temporaryFolder.newFolder("sender");

    final var senderSnapshotStoreFactory =
        new FileBasedSnapshotStoreFactory(senderScheduler.get(), 1);
    senderSnapshotStoreFactory.createReceivableSnapshotStore(senderRoot.toPath(), partitiondId);
    senderSnapshotStore = senderSnapshotStoreFactory.getConstructableSnapshotStore(partitiondId);

    final var receiverRoot = temporaryFolder.newFolder("received");
    receiverSnapshotStore =
        new FileBasedSnapshotStoreFactory(receiverScheduler.get(), 2)
            .createReceivableSnapshotStore(receiverRoot.toPath(), partitiondId);

    receiverSnapshotsDir =
        receiverRoot.toPath().resolve(FileBasedSnapshotStoreFactory.SNAPSHOTS_DIRECTORY);
    receiverPendingSnapshotsDir =
        receiverRoot.toPath().resolve(FileBasedSnapshotStoreFactory.PENDING_DIRECTORY);
  }

  @Test
  public void shouldNotCreateDirDirectlyOnNewReceivedSnapshot() {
    // given

    // when
    receiverSnapshotStore.newReceivedSnapshot("1-0-123-121");

    // then
    assertThat(receiverPendingSnapshotsDir.toFile()).isEmptyDirectory();
    assertThat(receiverSnapshotsDir.toFile()).isEmptyDirectory();
  }

  @Test
  public void shouldWriteChunkInPendingDirOnApplyChunk() throws Exception {
    // given
    final var index = 1L;
    final var term = 0L;

    final var persistedSnapshot = takeSnapshot(index, term);
    receiveSnapshot(persistedSnapshot);
    final var snapshotFiles = persistedSnapshot.getPath().toFile().list();

    // then
    assertThat(receiverSnapshotsDir.toFile()).isEmptyDirectory();

    assertThat(receiverPendingSnapshotsDir).exists();
    final var files = receiverPendingSnapshotsDir.toFile().listFiles();
    assertThat(files).hasSize(1);

    final var dir = files[0];
    final var snapshotFileList = dir.listFiles();
    assertThat(snapshotFileList).extracting(File::getName).containsExactlyInAnyOrder(snapshotFiles);
  }

  @Test
  public void shouldDeletePendingSnapshotDirOnAbort() throws Exception {
    // given
    final var index = 1L;
    final var term = 0L;
    final PersistedSnapshot persistedSnapshot = takeSnapshot(index, term);

    final var receivedSnapshot =
        receiverSnapshotStore.newReceivedSnapshot(persistedSnapshot.getId());
    try (final var snapshotChunkReader = persistedSnapshot.newChunkReader()) {
      while (snapshotChunkReader.hasNext()) {
        receivedSnapshot.apply(snapshotChunkReader.next());
      }
    }

    // when
    receivedSnapshot.abort().join();

    // then
    assertThat(receiverSnapshotsDir.toFile()).isEmptyDirectory();
    assertThat(receiverPendingSnapshotsDir.toFile()).isEmptyDirectory();
  }

  @Test
  public void shouldPurgePendingOnStore() throws Exception {
    // given
    final var index = 1L;
    final var term = 0L;
    takeAndReceiveSnapshot(index, term);

    // when
    receiverSnapshotStore.purgePendingSnapshots().join();

    // then
    assertThat(receiverSnapshotsDir.toFile()).isEmptyDirectory();
    assertThat(receiverPendingSnapshotsDir.toFile()).isEmptyDirectory();
  }

  @Test
  public void shouldPersistSnapshot() throws Exception {
    // given
    final var index = 1L;
    final var term = 0L;
    final var persistedSnapshot = takeSnapshot(index, term);
    final var receivedSnapshot = receiveSnapshot(persistedSnapshot);
    final var persistedSnapshotFiles = persistedSnapshot.getPath().toFile().list();

    // when
    final var snapshot = receivedSnapshot.persist().join();

    // then
    assertThat(snapshot).isNotNull();
    assertThat(receiverPendingSnapshotsDir.toFile()).isEmptyDirectory();

    assertThat(receiverSnapshotsDir).exists();
    final var files = receiverSnapshotsDir.toFile().listFiles();
    assertThat(files).hasSize(1);

    final var dir = files[0];
    assertThat(dir).hasName(snapshot.getId());

    final var snapshotFileList = dir.listFiles();
    assertThat(snapshotFileList)
        .extracting(File::getName)
        .containsExactlyInAnyOrder(persistedSnapshotFiles);
  }

  @Test
  public void shouldNotDeletePersistedSnapshotOnPurgePendingOnStore() throws Exception {
    // given
    final var index = 1L;
    final var term = 0L;

    final var persistedSnapshot = takeSnapshot(index, term);
    receiveSnapshot(persistedSnapshot).persist().join();
    final var persistedSnapshotFiles = persistedSnapshot.getPath().toFile().list();

    // when
    receiverSnapshotStore.purgePendingSnapshots().join();

    // then
    assertThat(receiverPendingSnapshotsDir.toFile()).isEmptyDirectory();

    assertThat(receiverSnapshotsDir).exists();
    final var files = receiverSnapshotsDir.toFile().listFiles();
    assertThat(files).hasSize(1);

    final var dir = files[0];
    assertThat(dir).hasName(persistedSnapshot.getId());

    final var snapshotFileList = dir.listFiles();
    assertThat(snapshotFileList)
        .extracting(File::getName)
        .containsExactlyInAnyOrder(persistedSnapshotFiles);
  }

  @Test
  public void shouldReplaceSnapshotOnNextSnapshot() throws Exception {
    // given
    final var index = 1L;
    final var term = 0L;
    takeAndReceiveSnapshot(index, term).persist().join();

    // when
    final var committedSnapshot = takeAndReceiveSnapshot(index + 1, term).persist().join();

    // then
    assertThat(receiverPendingSnapshotsDir.toFile()).isEmptyDirectory();

    final var snapshotDirs = receiverSnapshotsDir.toFile().listFiles();
    assertThat(snapshotDirs).hasSize(1);

    final var committedSnapshotDir = snapshotDirs[0];
    assertThat(FileBasedSnapshotMetadata.ofFileName(committedSnapshotDir.getName()))
        .map(FileBasedSnapshotMetadata::getIndex)
        .hasValue(2L);
    assertThat(committedSnapshotDir.listFiles())
        .extracting(File::getName)
        .containsExactly(committedSnapshot.getPath().toFile().list());
  }

  @Test
  public void shouldRemovePendingSnapshotOnCommittingSnapshot() throws Exception {
    // given
    final var index = 1L;
    final var term = 0L;
    takeAndReceiveSnapshot(index, term);

    // when
    final var committedSnapshot = takeAndReceiveSnapshot(index + 1, term).persist().join();
    final var committedSnapshotFiles = committedSnapshot.getPath().toFile().list();

    // then
    assertThat(receiverPendingSnapshotsDir.toFile()).isEmptyDirectory();

    final var snapshotDirs = receiverSnapshotsDir.toFile().listFiles();
    assertThat(snapshotDirs).hasSize(1);

    final var committedSnapshotDir = snapshotDirs[0];
    assertThat(FileBasedSnapshotMetadata.ofFileName(committedSnapshotDir.getName()))
        .map(FileBasedSnapshotMetadata::getIndex)
        .hasValue(2L);
    assertThat(committedSnapshotDir.listFiles())
        .extracting(File::getName)
        .containsExactlyInAnyOrder(committedSnapshotFiles);
  }

  @Test
  public void shouldNotRemovePendingSnapshotOnCommittingSnapshotWhenHigher() throws Exception {
    // given
    final var olderPersistedSnapshot = takeSnapshot(1L, 0L);
    final ReceivedSnapshot olderReceivedSnapshot = receiveSnapshot(olderPersistedSnapshot);
    final var newPersistedSnapshot = takeSnapshot(2L, 0L);

    // when
    receiveSnapshot(newPersistedSnapshot);
    olderReceivedSnapshot.persist().join();

    // then
    final var pendingSnapshotDirs = receiverPendingSnapshotsDir.toFile().listFiles();
    assertThat(pendingSnapshotDirs).hasSize(1);

    final var pendingSnapshotDir = pendingSnapshotDirs[0];
    assertThat(FileBasedSnapshotMetadata.ofFileName(pendingSnapshotDir.getName()))
        .map(FileBasedSnapshotMetadata::getIndex)
        .hasValue(2L);
    assertThat(pendingSnapshotDir.listFiles())
        .extracting(File::getName)
        .containsExactlyInAnyOrder(newPersistedSnapshot.getPath().toFile().list());
  }

  @Test
  public void shouldNotifyListenersOnNewSnapshot() throws Exception {
    // given
    final var listener = mock(PersistedSnapshotListener.class);
    final var index = 1L;
    final var term = 0L;
    receiverSnapshotStore.addSnapshotListener(listener);

    // when
    final var persistedSnapshot = takeAndReceiveSnapshot(index, term).persist().join();

    // then
    verify(listener, times(1)).onNewSnapshot(persistedSnapshot);
  }

  @Test
  public void shouldNotNotifyListenersOnNewSnapshotWhenDeregistered() throws Exception {
    // given
    final var listener = mock(PersistedSnapshotListener.class);
    final var index = 1L;
    final var term = 0L;
    senderSnapshotStore.addSnapshotListener(listener);
    senderSnapshotStore.removeSnapshotListener(listener);

    // when
    final var persistedSnapshot = takeAndReceiveSnapshot(index, term).persist().join();

    // then
    verify(listener, times(0)).onNewSnapshot(persistedSnapshot);
  }

  @Test
  public void shouldReceiveConcurrentlyButWriteInDifferentPendingDirs() throws Exception {
    // given
    final var index = 1L;
    final var term = 0L;
    final var persistedSnapshot = takeSnapshot(index, term);
    final var snapshotFiles = persistedSnapshot.getPath().toFile().list();

    // when
    receiveSnapshot(persistedSnapshot);
    receiveSnapshot(persistedSnapshot);

    // then
    assertThat(receiverSnapshotsDir.toFile()).isEmptyDirectory();

    assertThat(receiverPendingSnapshotsDir).exists();
    final var fileArray = receiverPendingSnapshotsDir.toFile().listFiles();
    assertThat(fileArray).isNotNull();
    final var files = Arrays.stream(fileArray).sorted().collect(Collectors.toList());
    assertThat(files).hasSize(2);

    final var dir = files.get(0);
    assertThat(dir).hasName(persistedSnapshot.getId() + "-1");

    final var snapshotFileList = dir.listFiles();
    assertThat(snapshotFileList).extracting(File::getName).containsExactlyInAnyOrder(snapshotFiles);

    final var otherDir = files.get(1);
    assertThat(otherDir).hasName(persistedSnapshot.getId() + "-2");

    final var otherSnapshotFileList = dir.listFiles();
    assertThat(otherSnapshotFileList)
        .extracting(File::getName)
        .containsExactlyInAnyOrder(snapshotFiles);
  }

  @Test
  public void shouldReceiveConcurrentlyAndPersist() throws Exception {
    // given
    final var index = 1L;
    final var term = 0L;
    final var persistedSnapshot = takeSnapshot(index, term);
    final var receivedSnapshot = receiveSnapshot(persistedSnapshot);
    final var otherReceivedSnapshot = receiveSnapshot(persistedSnapshot);

    // when
    final var receivedPersisted = receivedSnapshot.persist().join();
    final var otherReceivedPersisted = otherReceivedSnapshot.persist().join();

    // then
    assertThat(receivedPersisted).isEqualTo(otherReceivedPersisted);

    final var snapshotDirs = receiverSnapshotsDir.toFile().listFiles();
    assertThat(snapshotDirs).hasSize(1);

    final var committedSnapshotDir = snapshotDirs[0];
    assertThat(committedSnapshotDir).hasName(persistedSnapshot.getId());
    assertThat(committedSnapshotDir.listFiles())
        .extracting(File::getName)
        .containsExactlyInAnyOrder(persistedSnapshot.getPath().toFile().list());

    assertThat(receiverPendingSnapshotsDir.toFile()).isEmptyDirectory();
  }

  @Test
  public void shouldReceiveConcurrentlyAndPersistDoesnotDependOnTheOrder() throws Exception {
    // given
    final var index = 1L;
    final var term = 0L;
    final var persistedSnapshot = takeSnapshot(index, term);
    final var receivedSnapshot = receiveSnapshot(persistedSnapshot);
    final var otherReceivedSnapshot = receiveSnapshot(persistedSnapshot);

    // when
    final var otherReceivedPersisted = otherReceivedSnapshot.persist().join();
    final var receivedPersisted = receivedSnapshot.persist().join();

    // then
    assertThat(receivedPersisted).isEqualTo(otherReceivedPersisted);

    final var snapshotDirs = receiverSnapshotsDir.toFile().listFiles();
    assertThat(snapshotDirs).hasSize(1);

    final var committedSnapshotDir = snapshotDirs[0];
    assertThat(committedSnapshotDir).hasName(persistedSnapshot.getId());
    assertThat(committedSnapshotDir.listFiles())
        .extracting(File::getName)
        .containsExactlyInAnyOrder(persistedSnapshot.getPath().toFile().list());

    assertThat(receiverPendingSnapshotsDir.toFile()).isEmptyDirectory();
  }

  @Test
  public void shouldBeAbleToAbortAfterPersistingDoesntWork() throws Exception {
    // given
    final var index = 1L;
    final var term = 0L;
    final var transientSnapshot =
        senderSnapshotStore.newTransientSnapshot(index, term, 1, 0).orElseThrow();
    transientSnapshot.take(
        p -> takeSnapshot(p, List.of("file3", "file1", "file2"), List.of("content", "this", "is")));
    final var persistedSnapshot = transientSnapshot.persist().join();

    corruptSnapshotFile(persistedSnapshot, "file3");

    final var receivedSnapshot =
        receiverSnapshotStore.newReceivedSnapshot(persistedSnapshot.getId());
    try (final var snapshotChunkReader = persistedSnapshot.newChunkReader()) {
      while (snapshotChunkReader.hasNext()) {
        receivedSnapshot.apply(snapshotChunkReader.next());
      }
    }
    assertThatThrownBy(() -> receivedSnapshot.persist().join())
        .hasCauseInstanceOf(IllegalStateException.class);

    // when
    receivedSnapshot.abort().join();

    // then
    assertThat(receiverPendingSnapshotsDir.toFile()).isEmptyDirectory();
    assertThat(receiverSnapshotsDir.toFile()).isEmptyDirectory();
  }

  @Test
  public void shouldReturnFalseOnConsumingChunkWithInvalidSnapshotChecksum() throws Exception {
    // given
    final var index = 1L;
    final var term = 0L;
    final var transientSnapshot =
        senderSnapshotStore.newTransientSnapshot(index, term, 1, 0).orElseThrow();
    transientSnapshot.take(
        p -> takeSnapshot(p, List.of("file3", "file1", "file2"), List.of("content", "this", "is")));
    final var persistedSnapshot = transientSnapshot.persist().join();

    // when
    final var receivedSnapshot =
        receiverSnapshotStore.newReceivedSnapshot(persistedSnapshot.getId());

    try (final var snapshotChunkReader = persistedSnapshot.newChunkReader()) {
      var success = receivedSnapshot.apply(snapshotChunkReader.next()).join();
      assertThat(success).isTrue();
      success =
          receivedSnapshot
              .apply(
                  SnapshotChunkWrapper.withDifferentSnapshotChecksum(
                      snapshotChunkReader.next(), 0xCAFEL))
              .join();

      // then
      assertThat(success).isFalse();
    }
  }

  @Test
  public void shouldReturnFalseOnConsumingChunkWithInvalidChunkChecksum() throws Exception {
    // given
    final var index = 1L;
    final var term = 0L;
    final var transientSnapshot =
        senderSnapshotStore.newTransientSnapshot(index, term, 1, 0).orElseThrow();
    transientSnapshot.take(
        p -> takeSnapshot(p, List.of("file3", "file1", "file2"), List.of("content", "this", "is")));
    final var persistedSnapshot = transientSnapshot.persist().join();

    // when
    final var receivedSnapshot =
        receiverSnapshotStore.newReceivedSnapshot(persistedSnapshot.getId());

    try (final var snapshotChunkReader = persistedSnapshot.newChunkReader()) {
      final var success =
          receivedSnapshot
              .apply(
                  SnapshotChunkWrapper.withDifferentChecksum(snapshotChunkReader.next(), 0xCAFEL))
              .join();

      // then
      assertThat(success).isFalse();
    }
  }

  @Test
  public void shouldNotPersistWhenSnapshotChecksumIsWrong() throws Exception {
    // given
    final var index = 1L;
    final var term = 0L;
    final var transientSnapshot =
        senderSnapshotStore.newTransientSnapshot(index, term, 1, 0).orElseThrow();
    transientSnapshot.take(
        p -> takeSnapshot(p, List.of("file3", "file1", "file2"), List.of("content", "this", "is")));
    final var persistedSnapshot = transientSnapshot.persist().join();

    corruptSnapshotFile(persistedSnapshot, "file3");

    // when
    final var receivedSnapshot =
        receiverSnapshotStore.newReceivedSnapshot(persistedSnapshot.getId());
    try (final var snapshotChunkReader = persistedSnapshot.newChunkReader()) {
      while (snapshotChunkReader.hasNext()) {
        receivedSnapshot.apply(snapshotChunkReader.next());
      }
    }

    // then
    assertThatThrownBy(() -> receivedSnapshot.persist().join())
        .hasCauseInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Snapshot is corrupted");
  }

  @Test
  public void shouldNotPersistWhenSnapshotIsPartial() throws Exception {
    // given
    final var index = 1L;
    final var term = 0L;
    final var transientSnapshot =
        senderSnapshotStore.newTransientSnapshot(index, term, 1, 0).orElseThrow();
    transientSnapshot.take(
        p -> takeSnapshot(p, List.of("file3", "file1", "file2"), List.of("content", "this", "is")));
    final var persistedSnapshot = transientSnapshot.persist().join();

    deleteSnapshotFile(persistedSnapshot, "file3");

    // when
    final var receivedSnapshot =
        receiverSnapshotStore.newReceivedSnapshot(persistedSnapshot.getId());
    try (final var snapshotChunkReader = persistedSnapshot.newChunkReader()) {
      while (snapshotChunkReader.hasNext()) {
        receivedSnapshot.apply(snapshotChunkReader.next());
      }
    }

    // then
    assertThatThrownBy(() -> receivedSnapshot.persist().join())
        .hasCauseInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Snapshot is corrupted");
  }

  @Test
  public void shouldReturnFalseOnConsumingChunkWithNotEqualTotalCount() throws Exception {
    // given
    final var index = 1L;
    final var term = 0L;

    final var transientSnapshot =
        senderSnapshotStore.newTransientSnapshot(index, term, 1, 0).orElseThrow();
    transientSnapshot.take(
        p -> takeSnapshot(p, List.of("file3", "file1", "file2"), List.of("content", "this", "is")));
    final var persistedSnapshot = transientSnapshot.persist().join();

    // when
    final var receivedSnapshot =
        receiverSnapshotStore.newReceivedSnapshot(persistedSnapshot.getId());

    try (final var snapshotChunkReader = persistedSnapshot.newChunkReader()) {
      var success = receivedSnapshot.apply(snapshotChunkReader.next()).join();
      assertThat(success).isTrue();
      success =
          receivedSnapshot
              .apply(SnapshotChunkWrapper.withDifferentTotalCount(snapshotChunkReader.next(), 55))
              .join();

      // then
      assertThat(success).isFalse();
    }
  }

  @Test
  public void shouldNotPersistWhenTotalCountIsWrong() throws Exception {
    // given
    final var index = 1L;
    final var term = 0L;
    final var transientSnapshot =
        senderSnapshotStore.newTransientSnapshot(index, term, 1, 0).orElseThrow();
    transientSnapshot.take(
        p -> takeSnapshot(p, List.of("file3", "file1", "file2"), List.of("content", "this", "is")));
    final var persistedSnapshot = transientSnapshot.persist().join();
    final var receivedSnapshot =
        receiverSnapshotStore.newReceivedSnapshot(persistedSnapshot.getId());
    try (final var snapshotChunkReader = persistedSnapshot.newChunkReader()) {
      while (snapshotChunkReader.hasNext()) {
        receivedSnapshot.apply(
            SnapshotChunkWrapper.withDifferentTotalCount(snapshotChunkReader.next(), 2));
      }
    }

    // when - then
    assertThatThrownBy(() -> receivedSnapshot.persist().join())
        .hasCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  public void shouldReturnFalseOnConsumingChunkWithNotEqualSnapshotId() throws Exception {
    // given
    final var index = 1L;
    final var term = 0L;
    final var transientSnapshot =
        senderSnapshotStore.newTransientSnapshot(index, term, 1, 0).orElseThrow();
    transientSnapshot.take(
        p -> takeSnapshot(p, List.of("file3", "file1", "file2"), List.of("content", "this", "is")));
    final var persistedSnapshot = transientSnapshot.persist().join();

    // when
    final var receivedSnapshot =
        receiverSnapshotStore.newReceivedSnapshot(persistedSnapshot.getId());

    try (final var snapshotChunkReader = persistedSnapshot.newChunkReader()) {
      var success = receivedSnapshot.apply(snapshotChunkReader.next()).join();
      assertThat(success).isTrue();
      success =
          receivedSnapshot
              .apply(SnapshotChunkWrapper.withDifferentSnapshotId(snapshotChunkReader.next(), "id"))
              .join();

      // then
      assertThat(success).isFalse();
    }
  }

  private ReceivedSnapshot takeAndReceiveSnapshot(final long index, final long term)
      throws IOException {
    final PersistedSnapshot persistedSnapshot = takeSnapshot(index, term);

    return receiveSnapshot(persistedSnapshot);
  }

  private PersistedSnapshot takeSnapshot(final long index, final long term) {
    final var transientSnapshot =
        senderSnapshotStore.newTransientSnapshot(index, term, 1, 0).orElseThrow();
    transientSnapshot.take(this::takeSnapshot).join();
    return transientSnapshot.persist().join();
  }

  private ReceivedSnapshot receiveSnapshot(final PersistedSnapshot persistedSnapshot)
      throws IOException {
    final var receivedSnapshot =
        receiverSnapshotStore.newReceivedSnapshot(persistedSnapshot.getId());

    try (final var snapshotChunkReader = persistedSnapshot.newChunkReader()) {
      while (snapshotChunkReader.hasNext()) {
        receivedSnapshot.apply(snapshotChunkReader.next()).join();
      }
    }

    return receivedSnapshot;
  }

  private boolean takeSnapshot(final Path path) {
    return takeSnapshot(path, List.of("file1.txt"), List.of("This is the content"));
  }

  private boolean takeSnapshot(
      final Path path, final List<String> fileNames, final List<String> fileContents) {
    assertThat(fileNames).hasSameSizeAs(fileContents);

    try {
      FileUtil.ensureDirectoryExists(path);

      for (int i = 0; i < fileNames.size(); i++) {
        final var fileName = fileNames.get(i);
        final var fileContent = fileContents.get(i);
        Files.write(
            path.resolve(fileName), fileContent.getBytes(), CREATE_NEW, StandardOpenOption.WRITE);
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return true;
  }

  private void corruptSnapshotFile(
      final PersistedSnapshot persistedSnapshot, final String corruptedFileName)
      throws IOException {
    final var file = persistedSnapshot.getPath().resolve(corruptedFileName).toFile();
    try (final RandomAccessFile corruptedFile = new RandomAccessFile(file, "rw")) {
      corruptedFile.writeLong(123456L);
    }
  }

  private void deleteSnapshotFile(
      final PersistedSnapshot persistedSnapshot, final String deletedFileName) {
    assertThat(persistedSnapshot.getPath().resolve(deletedFileName).toFile().delete()).isTrue();
  }
}