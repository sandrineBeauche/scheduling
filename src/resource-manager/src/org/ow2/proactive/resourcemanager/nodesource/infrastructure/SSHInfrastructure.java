/*
 * ################################################################
 *
 * ProActive Parallel Suite(TM): The Java(TM) library for
 *    Parallel, Distributed, Multi-Core Computing for
 *    Enterprise Grids & Clouds
 *
 * Copyright (C) 1997-2010 INRIA/University of 
 * 				Nice-Sophia Antipolis/ActiveEon
 * Contact: proactive@ow2.org or contact@activeeon.com
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; version 3 of
 * the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307
 * USA
 *
 * If needed, contact us to obtain a release under GPL Version 2 
 * or a different license than the GPL.
 *
 *  Initial developer(s):               The ActiveEon Team
 *                        http://www.activeeon.com/
 *  Contributor(s):
 *
 * ################################################################
 * $$ACTIVEEON_INITIAL_DEV$$
 */
package org.ow2.proactive.resourcemanager.nodesource.infrastructure;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.objectweb.proactive.core.config.CentralPAPropertyRepository;
import org.objectweb.proactive.core.node.Node;
import org.objectweb.proactive.core.util.wrapper.BooleanWrapper;
import org.ow2.proactive.resourcemanager.exception.RMException;
import org.ow2.proactive.resourcemanager.nodesource.common.Configurable;
import org.ow2.proactive.utils.FileToBytesConverter;


/**
 * Acquires nodes over SSH given a list of hosts
 * <p>
 * Assumes all necessary ProActive configuration has already been performed:
 * ssh username, key directory, etc. This class won't handle it,
 * see {@link org.objectweb.proactive.core.ssh.SSHClient}.
 * <p>
 * Also assumes JRE & Scheduling installation paths are identical
 * on all hosts.
 * <p>
 * If you need more control over you deployment, you may consider
 * using {@link GCMInfrastructure} instead, which contains the 
 * functionalities of this Infrastructure, but requires more configuration.
 * 
 * 
 * @author The ProActive Team
 * @since ProActive Scheduling 2.0
 *
 */
public class SSHInfrastructure extends AbstractSSHInfrastructure {

    /**  */
	private static final long serialVersionUID = 21L;

	@Configurable(fileBrowser = true)
    protected File hostsList;

    /**
     * list of free hosts; if host AA has a capacity of 2 runtimes, the initial state
     * of this list will contain twice host AA.
     */
    private List<InetAddress> freeHosts = new ArrayList<InetAddress>();
    /**
     * holds the address of nodes acquired by this infrastructure
     */
    private List<InetAddress> running = new ArrayList<InetAddress>();
    /**
     * Acquisition timeout
     */
    private final static int timeout = 1000 * 60;

    /**
     * Acquire one node per available host
     */
    @Override
    public void acquireAllNodes() {
        int s = freeHosts.size();
        for (int i = 0; i < s; i++) {
            acquireNode();
        }
    }

    /**
     * Acquire one node on an available host
     */
    @Override
    public void acquireNode() {
        if (freeHosts.size() == 0) {
            logger.warn("Attempting to acquire nodes while all hosts are already deployed.");
            return;
        }

        final InetAddress host = freeHosts.remove(0);
        nodeSource.executeInParallel(new Runnable() {
            public void run() {
                try {
                    startRemoteNode(host);
                } catch (RMException e) {
                    logger.error("Could not acquire node " + host.toString(), e);
                    synchronized (freeHosts) {
                        freeHosts.add(host);
                    }
                    return;
                }
            }
        });
    }

    /**
     * Internal node acquisition method
     * <p>
     * Starts a PA runtime on remote host using SSH, register it manually in the
     * nodesource.
     * 
     * @param host hostname of the node on which a node should be started
     * @throws RMException acquisition failed
     */
    private void startRemoteNode(InetAddress host) throws RMException {

        // build the command used to start the runtime on the remote host

        String paJar = schedulingPath + "/dist/lib/jython-engine.jar:";
        paJar += schedulingPath + "/dist/lib/script-js.jar:";
        paJar += schedulingPath + "/dist/lib/jruby-engine.jar:";
        paJar += schedulingPath + "/dist/lib/ProActive.jar:";
        paJar += schedulingPath + "/dist/lib/ProActive_ResourceManager-client.jar:";
        paJar += schedulingPath + "/dist/lib/ProActive_Scheduler-worker.jar:";
        paJar += schedulingPath + "/dist/lib/ProActive_SRM-common-client.jar:";
        paJar += schedulingPath + "/dist/lib/commons-logging-1.0.4.jar";

        String cmd = this.javaPath + " -cp " + paJar;
        cmd += " " + CentralPAPropertyRepository.JAVA_SECURITY_POLICY.getCmdLine();
        cmd += schedulingPath + "/config/security.java.policy-server";
        cmd += " -Dproactive.communication.protocol=" + this.protocol;
        cmd += " -Dproactive.useIPaddress=true ";
        cmd += " " + this.javaOptions + " ";
        cmd += "org.objectweb.proactive.core.node.StartNode ";

        // generate the node name
        Random rand = new Random();
        String alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        String nn = "";
        for (int i = 0; i < 16; i++) {
            nn += alpha.charAt(rand.nextInt(alpha.length()));
        }
        String nodeName = "SSH-" + nodeSource.getName() + "-" + nn;
        // HACK HACK GNACK
        // node URL is guessed ... FIXME : does not work with PAMR
        String nodeUrl = this.protocol + "://" + host.getHostAddress() + ":" + this.port + "/" + nodeName;
        cmd += nodeName;

        Process p = runSSHCommand(host, cmd);

        running.add(host);

        long t1 = System.currentTimeMillis();
        while (true) {
            try {
                int exitCode = p.exitValue();
                if (exitCode != 0) {
                    logger.info("SSH subprocess at " + host.getHostName() + " exited abnormally (" +
                        exitCode + ").");
                    synchronized (running) {
                        p.destroy();
                        running.remove(host);
                        freeHosts.add(host);
                    }
                    break;
                }
            } catch (IllegalThreadStateException e) {
                // process has not returned yet
            }

            long t2 = System.currentTimeMillis();
            if (t2 - t1 > timeout || shutdown) {
                logger.info("Node at " + host.getHostName() + " timed out.");
                synchronized (running) {
                    p.destroy();
                    running.remove(host);
                    freeHosts.add(host);
                }
                break;
            }

            try {
                if (nodeSource.getStub().acquireNode(nodeUrl, nodeSource.getProvider()).booleanValue()) {
                    try {
                        // don't destroy the process if launched on localhost without SSH;
                        // it would kill it
                        if (!host.equals(InetAddress.getLocalHost()) &&
                            !host.equals(InetAddress.getByName("127.0.0.1"))) {
                            p.destroy();
                        }
                    } catch (UnknownHostException e) {
                    }
                    break;
                }
            } catch (Exception e) {
            }

            try {
                Thread.sleep(3000);
            } catch (Exception e) {
                return;
            }
        }
    }

    /**
     * Configures the Infrastructure
     * 
     * @param parameters
     *            parameters[0;5] : super
     *            parameters[6]   : path to the scheduling installation on the hosts from the list
     * @throws IllegalArgumentException configuration failed
     */
    @Override
    public BooleanWrapper configure(Object... parameters) {
        super.configure(parameters);

        if (parameters != null && parameters.length >= 7) {
            if (parameters[6] == null) {
                throw new IllegalArgumentException("Host file must be specified");
            }
            try {
                byte[] hosts = (byte[]) parameters[6];
                File f = File.createTempFile("hosts", "list");
                FileToBytesConverter.convertByteArrayToFile(hosts, f);
                readHosts(f);
                f.delete();
            } catch (Exception e) {
                throw new IllegalArgumentException("Could not read hosts file", e);
            }
        } else {
            throw new IllegalArgumentException("Invalid parameters for infrastructure creation");
        }
        return new BooleanWrapper(true);
    }

    /**
     * Internal host file parser
     * <p>
     * File format:
     * one host per line, optionally followed by a space and an integer describing the maximum
     * number of runtimes (1 if not specified). Example:
     * <pre>
     * example.com
     * example.org 5
     * example.net 3
     * </pre>
     * @param f the file from which hosts names are to be extracted
     * @throws IOException parsing failed
     */
    private void readHosts(File f) throws IOException {
        BufferedReader in = new BufferedReader(new FileReader(f));
        String line = "";

        while ((line = in.readLine()) != null) {
            if (line == "" || line.trim().length() == 0)
                continue;

            String[] elts = line.split(" ");
            int num = 1;
            if (elts.length > 1) {
                try {
                    num = Integer.parseInt(elts[1]);
                    if (num < 1) {
                        throw new IllegalArgumentException("Cannot launch less than one runtime per host.");
                    }
                } catch (Exception e) {
                    logger.warn("Error while parsing hosts file: " + e.getMessage());
                    num = 1;
                }
            }
            String host = elts[0];
            InetAddress addr = null;
            try {
                addr = InetAddress.getByName(host);
            } catch (Exception e) {
                logger.warn("Host " + host + " is not reachable, dropping.");
                continue;
            }
            for (int i = 0; i < num; i++) {
                this.freeHosts.add(addr);
            }
        }
    }

    @Override
    public void registerAcquiredNode(Node node) throws RMException {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeNode(Node node) throws RMException {
        synchronized (running) {
            for (InetAddress addr : running) {
                if (addr.equals(node.getVMInformation().getInetAddress())) {
                    running.remove(addr);
                    freeHosts.add(addr);

                    final Node n = node;
                    nodeSource.executeInParallel(new Runnable() {
                        public void run() {
                            try {
                                logger.info("Terminating the runtime " + n.getProActiveRuntime().getURL());
                                n.getProActiveRuntime().killRT(false);
                            } catch (Exception e) {
                            }
                        }
                    });

                    logger.info("Removed node at " + addr);
                    return;
                }
            }
        }
        logger.info("Could not find node at " + node.getVMInformation().getInetAddress() + " to remove");

    }

    /**
     * @return short description of the IM
     */
    public String getDescription() {
        return "Creates remote runtimes using SSH";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "SSH Infrastructure";
    }

}
