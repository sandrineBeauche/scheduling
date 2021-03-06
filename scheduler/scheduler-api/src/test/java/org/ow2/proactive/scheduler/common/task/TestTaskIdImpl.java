/*
 * ################################################################
 *
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
 * ################################################################
 * $$PROACTIVE_INITIAL_DEV$$
 */
package org.ow2.proactive.scheduler.common.task;

import org.ow2.proactive.scheduler.common.job.JobId;
import org.ow2.proactive.scheduler.core.properties.PASchedulerProperties;
import org.ow2.proactive.scheduler.job.JobIdImpl;
import org.ow2.proactive.scheduler.task.TaskIdImpl;
import org.junit.Assert;
import org.junit.Test;

import static org.junit.Assert.*;


public class TestTaskIdImpl {

    @Test
    public void testGetIterationIndex() throws Exception {
        TaskId taskNoIterationIndex = TaskIdImpl.createTaskId(new JobIdImpl(1L, "job"), "task", 1);
        TaskId taskIterationIndexSmallerThan9 = TaskIdImpl.createTaskId(new JobIdImpl(1L, "job"), "task#1", 1);
        TaskId taskIterationIndexGreaterThan9 = TaskIdImpl.createTaskId(new JobIdImpl(1L, "job"), "task#10", 1);
        TaskId taskReplicatedAndIterated = TaskIdImpl.createTaskId(new JobIdImpl(1L, "job"), "task#10*10", 1);

        assertEquals(0, taskNoIterationIndex.getIterationIndex());
        assertEquals(1, taskIterationIndexSmallerThan9.getIterationIndex());
        assertEquals(10, taskIterationIndexGreaterThan9.getIterationIndex());
        assertEquals(10, taskReplicatedAndIterated.getIterationIndex());
    }

    @Test
    public void testGetReplicationIndex() throws Exception {
        TaskId taskNoReplicationIndex = TaskIdImpl.createTaskId(new JobIdImpl(1L, "job"), "task", 1);
        TaskId taskReplicationIndexSmallerThan9 = TaskIdImpl.createTaskId(new JobIdImpl(1L, "job"), "task*1", 1);
        TaskId taskReplicationIndexGreaterThan9 = TaskIdImpl.createTaskId(new JobIdImpl(1L, "job"), "task*10", 1);
        TaskId taskReplicatedAndIterated = TaskIdImpl.createTaskId(new JobIdImpl(1L, "job"), "task#10*10", 1);

        assertEquals(0, taskNoReplicationIndex.getReplicationIndex());
        assertEquals(1, taskReplicationIndexSmallerThan9.getReplicationIndex());
        assertEquals(10, taskReplicationIndexGreaterThan9.getReplicationIndex());
        assertEquals(10, taskReplicatedAndIterated.getReplicationIndex());
    }

}
