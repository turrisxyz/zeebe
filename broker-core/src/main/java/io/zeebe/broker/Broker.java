package io.zeebe.broker;

import java.io.InputStream;

import io.zeebe.broker.clustering.ClusterComponent;
import io.zeebe.broker.logstreams.LogStreamsComponent;
import io.zeebe.broker.system.SystemComponent;
import io.zeebe.broker.system.SystemContext;
import io.zeebe.broker.task.TaskQueueComponent;
import io.zeebe.broker.transport.TransportComponent;
import io.zeebe.broker.workflow.WorkflowComponent;
import org.slf4j.Logger;

public class Broker implements AutoCloseable
{
    public static final Logger LOG = Loggers.SYSTEM_LOGGER;

    protected final SystemContext brokerContext;

    public Broker(String configFileLocation)
    {
        brokerContext = new SystemContext(configFileLocation);
        start();
    }

    public Broker(InputStream configStream)
    {
        brokerContext = new SystemContext(configStream);
        start();
    }

    protected void start()
    {
        brokerContext.addComponent(new SystemComponent());
        brokerContext.addComponent(new TransportComponent());
        brokerContext.addComponent(new LogStreamsComponent());
        brokerContext.addComponent(new TaskQueueComponent());
        brokerContext.addComponent(new WorkflowComponent());
        brokerContext.addComponent(new ClusterComponent());

        brokerContext.init();
    }

    @Override
    public void close()
    {
        brokerContext.close();

        LOG.info("Broker closed");
    }

    public SystemContext getBrokerContext()
    {
        return brokerContext;
    }
}
