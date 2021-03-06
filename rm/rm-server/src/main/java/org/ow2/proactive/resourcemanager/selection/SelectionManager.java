/*
 * ################################################################
 *
 * ProActive Parallel Suite(TM): The Java(TM) library for
 *    Parallel, Distributed, Multi-Core Computing for
 *    Enterprise Grids & Clouds
 *
 * Copyright (C) 1997-2011 INRIA/University of
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
package org.ow2.proactive.resourcemanager.selection;

import java.io.File;
import java.security.Permission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.objectweb.proactive.api.PAActiveObject;
import org.objectweb.proactive.api.PAFuture;
import org.objectweb.proactive.core.node.Node;
import org.objectweb.proactive.utils.NamedThreadFactory;
import org.ow2.proactive.authentication.principals.TokenPrincipal;
import org.ow2.proactive.permissions.PrincipalPermission;
import org.ow2.proactive.resourcemanager.authentication.Client;
import org.ow2.proactive.resourcemanager.core.RMCore;
import org.ow2.proactive.resourcemanager.core.properties.PAResourceManagerProperties;
import org.ow2.proactive.resourcemanager.exception.NotConnectedException;
import org.ow2.proactive.resourcemanager.rmnode.RMNode;
import org.ow2.proactive.resourcemanager.selection.policies.ShufflePolicy;
import org.ow2.proactive.resourcemanager.selection.topology.TopologyHandler;
import org.ow2.proactive.scripting.Script;
import org.ow2.proactive.scripting.ScriptException;
import org.ow2.proactive.scripting.ScriptResult;
import org.ow2.proactive.scripting.SelectionScript;
import org.ow2.proactive.utils.Criteria;
import org.ow2.proactive.utils.NodeSet;
import org.ow2.proactive.utils.appenders.MultipleFileAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.MDC;


/**
 * An interface of selection manager which is responsible for
 * nodes selection from a pool of free nodes for further scripts execution. 
 *
 */
public abstract class SelectionManager {

    private final static Logger logger = Logger.getLogger(SelectionManager.class);

    private RMCore rmcore;

    private static final int SELECTION_THEADS_NUMBER = PAResourceManagerProperties.RM_SELECTION_MAX_THREAD_NUMBER
            .getValueAsInt();

    private ExecutorService scriptExecutorThreadPool;

    private Set<String> inProgress;

    protected HashSet<String> authorizedSelectionScripts = null;

    // the policy for arranging nodes
    private SelectionPolicy selectionPolicy;

    public SelectionManager() {
    }

    public SelectionManager(RMCore rmcore) {
        this.rmcore = rmcore;
        this.scriptExecutorThreadPool = Executors.newFixedThreadPool(SELECTION_THEADS_NUMBER,
                new NamedThreadFactory("Selection manager threadpool"));
        this.inProgress = Collections.synchronizedSet(new HashSet<String>());

        String policyClassName = PAResourceManagerProperties.RM_SELECTION_POLICY.getValueAsString();
        try {
            Class<?> policyClass = Class.forName(policyClassName);
            selectionPolicy = (SelectionPolicy) policyClass.newInstance();
        } catch (Exception e) {
            logger.error("Cannot use the specified policy class: " + policyClassName, e);
            logger.warn("Using the default class: " + ShufflePolicy.class.getName());
            selectionPolicy = new ShufflePolicy();
        }

        loadAuthorizedScriptsSignatures();
    }

    /**
     * Loads authorized selection scripts.
     */
    public void loadAuthorizedScriptsSignatures() {
        String dirName = PAResourceManagerProperties.RM_EXECUTE_SCRIPT_AUTHORIZED_DIR.getValueAsString();
        if (dirName != null && dirName.length() > 0) {
            dirName = PAResourceManagerProperties.getAbsolutePath(dirName);

            logger.info("The resource manager will accept only selection scripts from " + dirName);
            File folder = new File(dirName);
            if (folder.exists() && folder.isDirectory()) {
                authorizedSelectionScripts = new HashSet<>();
                for (File file : folder.listFiles()) {
                    if (file.isFile()) {
                        try {
                            String script = SelectionScript.readFile(file);
                            logger.debug("Adding authorized selection script " + file.getAbsolutePath());
                            authorizedSelectionScripts.add(SelectionScript.digest(script));
                        } catch (Exception e) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                }
            } else {
                logger.error("Invalid dir name for authorized scripts " + dirName);
            }
        }
    }

    /**
     * Arranges nodes for script execution based on some criteria
     * for example previous execution statistics.
     * 
     * @param nodes - nodes list for script execution
     * @param scripts - set of selection scripts
     * @return collection of arranged nodes
     */
    public abstract List<RMNode> arrangeNodesForScriptExecution(final List<RMNode> nodes,
            List<SelectionScript> scripts);

    /**
     * Predicts script execution result. Allows to avoid duplicate script execution 
     * on the same node. 
     * 
     * @param script - script to execute
     * @param rmnode - target node
     * @return true if script will pass on the node 
     */
    public abstract boolean isPassed(SelectionScript script, RMNode rmnode);

    /**
     * Processes script result and updates knowledge base of 
     * selection manager at the same time.
     *
     * @param script - executed script
     * @param scriptResult - obtained script result
     * @param rmnode - node on which script has been executed
     * @return whether node is selected
     */
    public abstract boolean processScriptResult(SelectionScript script, ScriptResult<Boolean> scriptResult,
            RMNode rmnode);

    public NodeSet selectNodes(Criteria criteria, Client client) {

        maybeSetLoggingContext(criteria);
        try {
            return doSelectNodes(criteria, client);
        } finally {
            unsetLoggingContext();
        }

    }

    static void maybeSetLoggingContext(Criteria criteria) {
        if (criteria.getComputationDescriptors() != null) {
            // logging selection script execution into tasks logs
            MDC.put(MultipleFileAppender.FILE_NAMES, criteria.getComputationDescriptors());
        }
    }

    static void unsetLoggingContext() {
        MDC.remove(MultipleFileAppender.FILE_NAMES);
    }

    private NodeSet doSelectNodes(Criteria criteria, Client client) {
        boolean hasScripts = criteria.getScripts() != null && criteria.getScripts().size() > 0;
        if(logger.isInfoEnabled()){
            logger.info(client + " requested " + criteria.getSize() + " nodes with " + criteria.getTopology());
        }
        boolean loggerIsDebugEnabled = logger.isDebugEnabled();
        if (loggerIsDebugEnabled) {
            if (hasScripts) {
                logger.debug("Selection scripts:");
                for (SelectionScript s : criteria.getScripts()) {
                    logger.debug(s);
                }
            }

            if (criteria.getBlackList() != null && criteria.getBlackList().size() > 0) {
                logger.debug("Black list nodes:");
                for (Node n : criteria.getBlackList()) {
                    logger.debug(n);
                }
            }
        }

        // can throw Exception if topology is disabled
        TopologyHandler handler = RMCore.topologyManager.getHandler(criteria.getTopology());

        List<RMNode> freeNodes = rmcore.getFreeNodes();
        // filtering out the "free node list"
        // removing exclusion and checking permissions
        List<RMNode> filteredNodes = filterOut(freeNodes, criteria, client);

        if (filteredNodes.size() == 0) {
            if (loggerIsDebugEnabled) {
                logger.debug(client + " will get 0 nodes");
            }
            return new NodeSet();
        }

        // arranging nodes according to the selection policy
        // if could be shuffling or node source priorities
        List<RMNode> afterPolicyNodes = selectionPolicy.arrangeNodes(criteria.getSize(), filteredNodes,
                client);

        List<Node> matchedNodes;
        if (hasScripts) {
            // checking if all scripts are authorized
            checkAuthorizedScripts(criteria.getScripts());

            // arranging nodes for script execution
            List<RMNode> arrangedNodes = arrangeNodesForScriptExecution(afterPolicyNodes,
                    criteria.getScripts());

            if (criteria.getTopology().isTopologyBased()) {
                // run scripts on all available nodes
                matchedNodes = runScripts(arrangedNodes, criteria);
            } else {
                // run scripts not on all nodes, but always on missing number of nodes
                // until required node set is found
                matchedNodes = new LinkedList<>();
                while (matchedNodes.size() < criteria.getSize()) {
                    int numberOfNodesForScriptExecution = criteria.getSize() - matchedNodes.size();

                    if (numberOfNodesForScriptExecution < SELECTION_THEADS_NUMBER) {
                        // we can run "SELECTION_THEADS_NUMBER" scripts in parallel
                        // in case when we need less nodes it still useful to
                        // the full capacity of the thread pool to find nodes quicker

                        // it is not important if we find more nodes than needed
                        // subset will be selected later (topology handlers)
                        numberOfNodesForScriptExecution = SELECTION_THEADS_NUMBER;
                    }

                    List<RMNode> subset = arrangedNodes.subList(0,
                            Math.min(numberOfNodesForScriptExecution, arrangedNodes.size()));
                    matchedNodes.addAll(runScripts(subset, criteria));
                    // removing subset of arrangedNodes
                    subset.clear();

                    if (arrangedNodes.size() == 0) {
                        break;
                    }
                }
            }
            if (loggerIsDebugEnabled) {
                logger.debug(matchedNodes.size() + " nodes found after scripts execution for " + client);
            }
        } else {
            matchedNodes = new LinkedList<>();
            for (RMNode node : afterPolicyNodes) {
                matchedNodes.add(node.getNode());
            }
        }

        // now we have a list of nodes which match to selection scripts
        // selecting subset according to topology requirements
        // TopologyHandler handler = RMCore.topologyManager.getHandler(topologyDescriptor);

        if (criteria.getTopology().isTopologyBased() && loggerIsDebugEnabled) {
            logger.debug("Filtering nodes with topology " + criteria.getTopology());
        }
        NodeSet selectedNodes = handler.select(criteria.getSize(), matchedNodes);

        if (selectedNodes.size() < criteria.getSize() && !criteria.isBestEffort()) {
            selectedNodes.clear();
            if (selectedNodes.getExtraNodes() != null) {
                selectedNodes.getExtraNodes().clear();
            }
        }

        // the nodes are selected, now mark them as busy.
        for (Node node : selectedNodes) {
            try {
                // Synchronous call
                rmcore.setBusyNode(node.getNodeInformation().getURL(), client);
            } catch (NotConnectedException e) {
                // client has disconnected during getNodes request
                logger.warn(e.getMessage(), e);
                return null;
            }
        }
        // marking extra selected nodes as busy
        if (selectedNodes.size() > 0 && selectedNodes.getExtraNodes() != null) {
            for (Node node : new LinkedList<>(selectedNodes.getExtraNodes())) {
                try {
                    // synchronous call
                    rmcore.setBusyNode(node.getNodeInformation().getURL(), client);
                } catch (NotConnectedException e) {
                    // client has disconnected during getNodes request
                    logger.warn(e.getMessage(), e);
                    return null;
                }
            }
        }

        if (logger.isInfoEnabled()) {
            String extraNodes = selectedNodes.getExtraNodes() != null &&
                selectedNodes.getExtraNodes().size() > 0 ? "and " + selectedNodes.getExtraNodes().size() +
                " extra nodes" : "";
            logger.info(client + " will get " + selectedNodes.size() + " nodes " + extraNodes);
        }

        if (loggerIsDebugEnabled) {
            for (Node n : selectedNodes) {
                logger.debug(n.getNodeInformation().getURL());
            }
        }

        return selectedNodes;
    }

    /**
     * Checks is all scripts are authorized. If not throws an exception.
     */
    private void checkAuthorizedScripts(List<SelectionScript> scripts) {
        if (authorizedSelectionScripts == null || scripts == null)
            return;

        for (SelectionScript script : scripts) {
            if (!authorizedSelectionScripts.contains(SelectionScript.digest(script.getScript()))) {
                // unauthorized selection script
                throw new SecurityException("Cannot execute unauthorized script " + script.getScript());
            }
        }
    }

    /**
     * Runs scripts on given set of nodes and returns matched nodes.
     * It blocks until all results are obtained.
     *
     * @param candidates nodes to execute scripts on
     * @param criteria contains a set of scripts to execute on each node
     * @return nodes matched to all scripts
     */
    private List<Node> runScripts(List<RMNode> candidates, Criteria criteria) {
        List<Node> matched = new LinkedList<>();

        if (candidates.size() == 0) {
            return matched;
        }

        // creating script executors object to be run in dedicated thread pool
        List<Callable<Node>> scriptExecutors = new LinkedList<>();
        synchronized (inProgress) {
            if (inProgress.size() > 0) {
                logger.warn(inProgress.size() + " nodes are in process of script execution");
                for (String nodeName : inProgress) {
                    logger.warn(nodeName);

                }
                logger.warn("Something is wrong on these nodes");
            }
            for (RMNode node : candidates) {
                if (!inProgress.contains(node.getNodeURL())) {
                    inProgress.add(node.getNodeURL());
                    scriptExecutors.add(new ScriptExecutor(node, criteria, this));
                }
            }
        }

        try {
            // launching
            Collection<Future<Node>> matchedNodes = scriptExecutorThreadPool.invokeAll(scriptExecutors,
                    PAResourceManagerProperties.RM_SELECT_SCRIPT_TIMEOUT.getValueAsInt(),
                    TimeUnit.MILLISECONDS);
            int index = 0;

            // waiting for the results
            for (Future<Node> futureNode : matchedNodes) {
                if (!futureNode.isCancelled()) {
                    Node node;
                    try {
                        node = futureNode.get();
                        if (node != null) {
                            matched.add(node);
                        }
                    } catch (InterruptedException e) {
                        logger.warn("Interrupting the selection manager");
                        return matched;
                    } catch (ExecutionException e) {
                        logger.warn("Ignoring exception in selection script: " + e.getMessage());
                    }
                } else {
                    // no script result was obtained
                    logger.warn("Timeout on " + scriptExecutors.get(index));
                    // in this case scriptExecutionFinished may not be called
                    scriptExecutionFinished(((ScriptExecutor) scriptExecutors.get(index)).getRMNode()
                            .getNodeURL());
                }
                index++;
            }
        } catch (InterruptedException e1) {
            logger.warn("Interrupting the selection manager");
        }

        return matched;
    }

    /**
     * Removes exclusion nodes and nodes not accessible for the client
     */
    private List<RMNode> filterOut(List<RMNode> freeNodes, Criteria criteria, Client client) {

        NodeSet exclusion = criteria.getBlackList();

        boolean nodeWithTokenRequested = criteria.getNodeAccessToken() != null &&
            criteria.getNodeAccessToken().length() > 0;

        TokenPrincipal tokenPrincipal = null;
        if (nodeWithTokenRequested) {
            logger.debug("Node access token specified " + criteria.getNodeAccessToken());

            tokenPrincipal = new TokenPrincipal(criteria.getNodeAccessToken());
            client.getSubject().getPrincipals().add(tokenPrincipal);
        }

        List<RMNode> filteredList = new ArrayList<>();
        HashSet<Permission> clientPermissions = new HashSet<>();
        for (RMNode node : freeNodes) {
            // checking the permission
            try {
                if (!clientPermissions.contains(node.getUserPermission())) {
                    client.checkPermission(node.getUserPermission(), client +
                        " is not authorized to get the node " + node.getNodeURL() + " from " +
                        node.getNodeSource().getName());
                    clientPermissions.add(node.getUserPermission());
                }
            } catch (SecurityException e) {
                // client does not have an access to this node
                logger.debug(e.getMessage());
                continue;
            }

            // if the node access token is specified we filtered out all nodes
            // with other tokens but must also filter out nodes without tokens
            if (nodeWithTokenRequested && !node.isProtectedByToken()) {
                continue;
            }

            // if client has AllPermissions he still can get a node with any token
            // we will avoid it here
            if (nodeWithTokenRequested) {
                PrincipalPermission perm = (PrincipalPermission) node.getUserPermission();
                // checking explicitly that node has this token identity
                if (!perm.hasPrincipal(tokenPrincipal)) {
                    if (logger.isDebugEnabled()) {
                        logger.debug(client + " does not have required token to get the node " +
                            node.getNodeURL() + " from " + node.getNodeSource().getName());
                    }
                    continue;
                }
            }

            if (!contains(exclusion, node)) {
                filteredList.add(node);
            }
        }
        return filteredList;
    }

    public <T> List<ScriptResult<T>> executeScript(final Script<T> script, final Collection<RMNode> nodes) {
        // TODO: add a specific timeout for script execution
        final int timeout = PAResourceManagerProperties.RM_EXECUTE_SCRIPT_TIMEOUT.getValueAsInt();
        final ArrayList<Callable<ScriptResult<T>>> scriptExecutors = new ArrayList<>(
                nodes.size());

        // Execute the script on each selected node
        for (final RMNode node : nodes) {
            scriptExecutors.add(new Callable<ScriptResult<T>>() {
                @Override
                public ScriptResult<T> call() throws Exception {
                    // Execute with a timeout the script by the remote handler 
                    // and always async-unlock the node, exceptions will be treated as ExecutionException
                    try {
                        ScriptResult<T> res = node.executeScript(script);
                        PAFuture.waitFor(res, timeout);
                        return res;
                        //return PAFuture.getFutureValue(res, timeout);
                    } finally {
                        // cleaning the node
                        try {
                            node.clean();
                        } catch (Throwable ex) {
                            logger.error("Cannot clean the node " + node.getNodeURL(), ex);
                        }

                        SelectionManager.this.rmcore.unlockNodes(Collections.singleton(node.getNodeURL()));
                    }
                }

                @Override
                public String toString() {
                    return "executing script on " + node.getNodeURL();
                }
            });
        }

        // Invoke all Callables and get the list of futures
        List<Future<ScriptResult<T>>> futures = null;
        try {
            futures = this.scriptExecutorThreadPool
                    .invokeAll(scriptExecutors, timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting, unable to execute all scripts", e);
            Thread.currentThread().interrupt();
        }

        final List<ScriptResult<T>> results = new LinkedList<>();

        int index = 0;
        // waiting for the results
        for (final Future<ScriptResult<T>> future : futures) {
            final String description = scriptExecutors.get(index++).toString();
            ScriptResult<T> result = null;
            try {
                result = future.get();
            } catch (CancellationException e) {
                result = new ScriptResult<>(new ScriptException("Cancelled due to timeout expiration when " +
                        description, e));
            } catch (InterruptedException e) {
                result = new ScriptResult<>(new ScriptException("Cancelled due to interruption when " +
                        description));
            } catch (ExecutionException e) {
                // Unwrap the root exception 
                Throwable rex = e.getCause();
                result = new ScriptResult<>(new ScriptException("Exception occured in script call when " +
                        description, rex));
            }
            results.add(result);
        }

        return results;
    }

    /**
     * Indicates that script execution is finished for the node with specified url.
     */
    public void scriptExecutionFinished(String nodeUrl) {
        synchronized (inProgress) {
            inProgress.remove(nodeUrl);
        }
    }

    /**
     * Handles shut down of the selection manager
     */
    public void shutdown() {
        // shutdown the thread pool without waiting for script execution completions
        scriptExecutorThreadPool.shutdownNow();
        PAActiveObject.terminateActiveObject(false);
    }

    /**
     * Return true if node contains the node set.
     *
     * @param nodeset - a list of nodes to inspect
     * @param node - a node to find
     * @return true if node contains the node set.
     */
    private boolean contains(NodeSet nodeset, RMNode node) {
        if (nodeset == null)
            return false;

        for (Node n : nodeset) {
            try {
                if (n.getNodeInformation().getURL().equals(node.getNodeURL())) {
                    return true;
                }
            } catch (Exception e) {
                continue;
            }
        }
        return false;
    }

}
