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

import org.ow2.proactive.utils.Tools;
import org.ow2.proactive_grid_cloud_portal.cli.ApplicationContext;
import org.ow2.proactive_grid_cloud_portal.cli.CLIException;
import org.ow2.proactive_grid_cloud_portal.cli.cmd.AbstractJobCommand;
import org.ow2.proactive_grid_cloud_portal.cli.cmd.Command;
import org.ow2.proactive_grid_cloud_portal.cli.utils.StringUtility;
import org.ow2.proactive_grid_cloud_portal.common.SchedulerRestInterface;
import org.ow2.proactive_grid_cloud_portal.scheduler.dto.JobStateData;

import java.util.List;

/**
 * @author  the activeeon team.
 */
public class ListJobTasksCommand extends AbstractJobTagPaginatedCommand implements Command {

    private final int MAX_PAGE_SIZE = 50;
    private int offset = 0;
    private int limit = MAX_PAGE_SIZE;
    
    public ListJobTasksCommand(String jobId){
        super(jobId);
    }

    public ListJobTasksCommand(String jobId, String tag){
        super(jobId, tag);
    }
    
    public ListJobTasksCommand(String jobId, String tag, int offset, int limit) {
        super(jobId, tag, offset, limit);
    }
    
    public ListJobTasksCommand(String jobId, int offset, int limit) {
        super(jobId, offset, limit);
    }

    @Override
    public void execute(ApplicationContext currentContext) throws CLIException {
        SchedulerRestInterface scheduler = currentContext.getRestClient().getScheduler();
        try {
            List<String> tasks = null;
            if(this.tag != null){
                tasks = scheduler.getJobTasksIdsByTag(currentContext.getSessionId(), jobId, tag);
            }
            else{
                tasks = scheduler.getJobTasksIds(currentContext.getSessionId(), jobId);
            }

            resultStack(currentContext).push(tasks);

            if (!currentContext.isSilent()) {
                writeLine(currentContext, "%s", tasks);
            }
        } catch (Exception e) {
            String message = null;
            if(this.tag == null){
                message = String.format("An error occurred while retrieving %s tasks:", job());
            }
            else{
                message = String.format("An error occurred while retrieving %s tasks filtered by tag %s:", job(), tag);
            }
            handleError(message, e, currentContext);
        }
    }
}
