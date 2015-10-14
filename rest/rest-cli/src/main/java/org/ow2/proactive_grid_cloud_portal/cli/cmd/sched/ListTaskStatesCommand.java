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
package org.ow2.proactive_grid_cloud_portal.cli.cmd.sched;

import org.ow2.proactive_grid_cloud_portal.cli.ApplicationContext;
import org.ow2.proactive_grid_cloud_portal.cli.CLIException;
import org.ow2.proactive_grid_cloud_portal.cli.cmd.AbstractJobCommand;
import org.ow2.proactive_grid_cloud_portal.cli.cmd.Command;
import org.ow2.proactive_grid_cloud_portal.cli.utils.StringUtility;
import org.ow2.proactive_grid_cloud_portal.common.SchedulerRestInterface;
import org.ow2.proactive_grid_cloud_portal.scheduler.dto.TaskStateData;

import java.util.List;

/**
 * @author  the activeeon team.
 */
public class ListTaskStatesCommand extends AbstractJobTagPaginatedCommand implements Command {
    
    public ListTaskStatesCommand(String jobId){
        super(jobId);
    }

    public ListTaskStatesCommand(String jobId, String tag){
        super(jobId, tag);
    }
    
    public ListTaskStatesCommand(String jobId, String tag, String offset, String limit) {
        super(jobId, tag, offset, limit);
    }
    
    public ListTaskStatesCommand(String jobId, String offset, String limit) {
        super(jobId, offset, limit);
    }


    @Override
    public void execute(ApplicationContext currentContext) throws CLIException {
        SchedulerRestInterface scheduler = currentContext.getRestClient().getScheduler();
        try {
            List<TaskStateData> tasks = null;
            if (this.tag == null) {
                if (this.limit == 0) {
                    tasks = scheduler.getJobTaskStates(currentContext.getSessionId(), jobId);
                } else {
                    tasks = scheduler.getJobTaskStatesPaginated(currentContext.getSessionId(), jobId, offset,
                            limit);
                }
            } else {
                if (this.limit == 0) {
                    tasks = scheduler.getJobTaskStatesByTag(currentContext.getSessionId(), jobId, tag);
                } else {
                    tasks = scheduler.getJobTaskStatesByTagPaginated(currentContext.getSessionId(), jobId,
                            tag, offset, limit);
                }

            }
            resultStack(currentContext).push(tasks);
            if (!currentContext.isSilent()) {
                writeLine(currentContext, "%s", StringUtility.taskStatesAsString(tasks, false));
            }
        } catch (Exception e) {
            handleError(String.format("An error occurred while retrieving %s state:", job()), e,
                    currentContext);
        }
    }
}
