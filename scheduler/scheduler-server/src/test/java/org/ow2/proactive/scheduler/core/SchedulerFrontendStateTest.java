package org.ow2.proactive.scheduler.core;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.objectweb.proactive.core.UniqueID;
import org.ow2.proactive.scheduler.core.jmx.SchedulerJMXHelper;
import org.ow2.proactive.scheduler.core.jmx.mbean.RuntimeDataMBeanImpl;
import org.ow2.proactive.scheduler.core.properties.PASchedulerProperties;
import org.ow2.proactive.scheduler.job.UserIdentificationImpl;
import org.ow2.tests.ProActiveTest;
import org.junit.Test;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public class SchedulerFrontendStateTest extends ProActiveTest {

    @Test // SCHEDULING-2242
    public void session_removal_should_not_throw_concurrent_modification_exception() throws Exception {
        PASchedulerProperties.SCHEDULER_USER_SESSION_TIME.updateProperty("1");

        SchedulerJMXHelper mockJMX = mock(SchedulerJMXHelper.class);
        when(mockJMX.getSchedulerRuntimeMBean()).thenReturn(new RuntimeDataMBeanImpl(null));

        final SchedulerFrontendState schedulerFrontendState = new SchedulerFrontendState(
            new SchedulerStateImpl(), mockJMX);

        // create a bunch of active sessions, they will be removed in 1s
        for (int i = 0; i < 100; i++) {
            UserIdentificationImpl identification = new UserIdentificationImpl("john");
            identification.setHostName("localhost");
            schedulerFrontendState.connect(new UniqueID("abc" + i), identification, null);
        }

        // use the FrontendState continuously to query the active sessions
        ExecutorService executor = Executors.newFixedThreadPool(1);
        Future<Object> noException = executor.submit(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                for (;;) {
                    schedulerFrontendState.getUsers();
                }
            }
        });

        try {
            noException.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            fail("Should exit with timeout exception " + e.getMessage());
        } catch (TimeoutException e) {
            // expected timeout exception after two seconds
        }
    }
}