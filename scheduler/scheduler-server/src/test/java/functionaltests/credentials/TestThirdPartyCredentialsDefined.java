/*
 *  *
 * ProActive Parallel Suite(TM): The Java(TM) library for
 *    Parallel, Distributed, Multi-Core Computing for
 *    Enterprise Grids & Clouds
 *
 * Copyright (C) 1997-2015 INRIA/University of
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
 *  * $$ACTIVEEON_INITIAL_DEV$$
 */
package functionaltests.credentials;

import org.ow2.proactive.scheduler.common.job.JobId;
import org.ow2.proactive.scheduler.common.job.JobResult;
import org.ow2.proactive.scheduler.common.job.TaskFlowJob;
import org.ow2.proactive.scheduler.common.task.ScriptTask;
import org.ow2.proactive.scheduler.common.task.TaskResult;
import org.ow2.proactive.scripting.SimpleScript;
import org.ow2.proactive.scripting.TaskScript;
import functionaltests.utils.SchedulerFunctionalTest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;


public class TestThirdPartyCredentialsDefined extends SchedulerFunctionalTest {

    @Test
    public void testThatCredentialsAreCreatedIfThirdPartyCredentialHaveBeenDefined() throws Exception {
        schedulerHelper.getSchedulerInterface().putThirdPartyCredential("mykey", "myvalue");
        assertEquals("{mykey: myvalue}", createAndSubmitTaskPrintingCredentials());
    }

    public String createAndSubmitTaskPrintingCredentials() throws Exception {
        ScriptTask scriptTask = new ScriptTask();
        scriptTask.setName("task");
        scriptTask.setScript(
                new TaskScript(new SimpleScript("print credentials", "python")));

        TaskFlowJob job = new TaskFlowJob();
        job.addTask(scriptTask);

        JobId id = schedulerHelper.submitJob(job);

        schedulerHelper.waitForEventJobFinished(id);

        JobResult jobResult = schedulerHelper.getJobResult(id);

        TaskResult result = jobResult.getResult(scriptTask.getName());

        return result.getOutput().getStdoutLogs(false).replaceAll("\n|\r", "");
    }

}
