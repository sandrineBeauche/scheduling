/*
 * ################################################################
 *
 * ProActive Parallel Suite(TM): The Java(TM) library for
 *    Parallel, Distributed, Multi-Core Computing for
 *    Enterprise Grids & Clouds
 *
 * Copyright (C) 1997-2012 INRIA/University of
 *                 Nice-Sophia Antipolis/ActiveEon
 * Contact: proactive@ow2.org or contact@activeeon.com
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; version 3 of
 * the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307
 * USA
 *
 * If needed, contact us to obtain a release under GPL Version 2 or 3
 * or a different license than the AGPL.
 *
 *  Initial developer(s):               The ProActive Team
 *                        http://proactive.inria.fr/team_members.htm
 *  Contributor(s):
 *
 * ################################################################
 * $$ACTIVEEON_INITIAL_DEV$$
 */

package org.ow2.proactive_grid_cloud_portal.cli.cmd.scheduler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ow2.proactive_grid_cloud_portal.cli.ApplicationContextImpl;
import org.ow2.proactive_grid_cloud_portal.cli.cmd.Command;
import org.ow2.proactive_grid_cloud_portal.cli.cmd.sched.GetJobOutputCommand;
import org.ow2.proactive_grid_cloud_portal.cli.cmd.sched.ListTaskStatesCommand;
import org.ow2.proactive_grid_cloud_portal.scheduler.dto.TaskStateData;
import org.ow2.proactive_grid_cloud_portal.scheduler.exception.UnknownJobRestException;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@RunWith(PowerMockRunner.class)
@PrepareForTest(ApplicationContextImpl.class)
@PowerMockIgnore({"javax.script.*", "com.sun.script.*", "org.fusesource.jansi.internal.Kernel32"})
public class GetJobOutputCommandTest extends AbstractJobTagCommandTest{


    protected String expectedOutputJobId = "an output for all the job";

    protected String expectedOutputJobIdTag = "an output for a subset of tasks";


    @Before
    public void setUp() throws Exception{
        super.setUp();
    }


    @Test
    public void testCommandJobIdOnly() throws Exception{
        when(restApi.jobLogs(anyString(), eq(jobId))).thenReturn(expectedOutputJobId);
        executeTest(jobId);
        String out = capturedOutput.toString();
        assertThat(out, equalTo(expectedOutputJobId + System.lineSeparator()));
    }


    @Test
    public void testCommandJobIdTag() throws Exception{
        when(restApi.tasklogByTag(anyString(), eq(jobId), eq(tag))).thenReturn(expectedOutputJobIdTag);
        executeTest(jobId, tag);

        String out = capturedOutput.toString();
        System.out.println(out);

        assertThat(out, equalTo(expectedOutputJobIdTag + System.lineSeparator()));
    }

    @Test
    public void testCommandUnknownJob() throws Exception{
        when(restApi.jobLogs(anyString(), eq(unknownJobId))).thenThrow(exceptionUnknownJob);
        executeTest(unknownJobId);

        String out = capturedOutput.toString();
        System.out.println(out);

        assertThat(out, equalTo("An error occurred while retrieving job('2') output:" + System.lineSeparator() +
                "Error Message: Job 2 does not exists" + System.lineSeparator()));
    }


    @Test
    public void testCommandJobIdUnknownTag() throws Exception{
        when(restApi.tasklogByTag(anyString(), eq(jobId), eq(unknownTag))).thenReturn("");
        executeTest(jobId, unknownTag);

        String out = capturedOutput.toString();
        System.out.println(out);

        assertThat(out, equalTo(System.lineSeparator()));
    }


    @Test
    public void testCommandUnknownJobIdUnknownTag() throws Exception{
        when(restApi.tasklogByTag(anyString(), eq(unknownJobId), anyString())).thenThrow(exceptionUnknownJob);
        executeTest(unknownJobId, unknownTag);

        String out = capturedOutput.toString();
        System.out.println(out);

        assertThat(out, equalTo("An error occurred while retrieving job('2') output:" + System.lineSeparator() +
                "Error Message: Job 2 does not exists" + System.lineSeparator()));
    }


    @Test
    public void testJobIdOnlyFromInteractive() throws Exception{
        typeLine("joboutput(1)");
        executeTestInteractive();
        verify(restApi).jobLogs(anyString(), eq("1"));
    }


    @Test
    public void testJobIdTagFromInteractive() throws Exception{
        typeLine("joboutput(1, 'LOOP-T2-1')");
        executeTestInteractive();
        verify(restApi).tasklogByTag(anyString(), eq("1"), eq("LOOP-T2-1"));
    }


    @Override
    protected void executeCommandWithArgs(Object... args) {
        Command command = null;
        if(args.length == 1){
            command = new GetJobOutputCommand((String) args[0]);
        }
        else{
            command = new GetJobOutputCommand((String) args[0], (String) args[1]);
        }
        command.execute(context);
    }

}
