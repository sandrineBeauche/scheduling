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
package org.ow2.proactive.scheduler.common.job.factories;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.events.XMLEvent;

import org.objectweb.proactive.extensions.dataspaces.vfs.selector.FileSelector;
import org.ow2.proactive.scheduler.common.exception.JobCreationException;
import org.ow2.proactive.scheduler.common.job.Job;
import org.ow2.proactive.scheduler.common.job.JobId;
import org.ow2.proactive.scheduler.common.job.JobPriority;
import org.ow2.proactive.scheduler.common.job.JobType;
import org.ow2.proactive.scheduler.common.job.TaskFlowJob;
import org.ow2.proactive.scheduler.common.task.CommonAttribute;
import org.ow2.proactive.scheduler.common.task.ForkEnvironment;
import org.ow2.proactive.scheduler.common.task.JavaTask;
import org.ow2.proactive.scheduler.common.task.NativeTask;
import org.ow2.proactive.scheduler.common.task.ParallelEnvironment;
import org.ow2.proactive.scheduler.common.task.RestartMode;
import org.ow2.proactive.scheduler.common.task.ScriptTask;
import org.ow2.proactive.scheduler.common.task.Task;
import org.ow2.proactive.scheduler.common.task.dataspaces.InputAccessMode;
import org.ow2.proactive.scheduler.common.task.dataspaces.OutputAccessMode;
import org.ow2.proactive.scheduler.common.task.flow.FlowActionType;
import org.ow2.proactive.scheduler.common.task.flow.FlowBlock;
import org.ow2.proactive.scheduler.common.task.flow.FlowScript;
import org.ow2.proactive.scheduler.core.properties.PASchedulerProperties;
import org.ow2.proactive.scripting.Script;
import org.ow2.proactive.scripting.SelectionScript;
import org.ow2.proactive.scripting.SimpleScript;
import org.ow2.proactive.scripting.TaskScript;
import org.ow2.proactive.topology.descriptor.ThresholdProximityDescriptor;
import org.ow2.proactive.topology.descriptor.TopologyDescriptor;
import org.ow2.proactive.utils.Tools;
import org.apache.log4j.Logger;
import org.iso_relax.verifier.VerifierConfigurationException;
import org.xml.sax.SAXException;

import static org.ow2.proactive.scheduler.common.util.VariableSubstitutor.filterAndUpdate;


/**
 * StaxJobFactory provide an implementation of the JobFactory using StAX
 *
 * @author The ProActive Team
 * @since ProActive Scheduling 0.9.1
 *
 * $Id$
 */
public class StaxJobFactory extends JobFactory {

    public static Logger logger = Logger.getLogger(StaxJobFactory.class);

    /** XML input factory */
    private XMLInputFactory xmlif = null;
    /** Instance variables of the XML files. */
    private HashMap<String, String> variables = new HashMap<>();
    /** Job instance : to be sent to the user once created. */
    private Job job = null;
    /** file relative path (relative file path (js) given in XML will be relative to this path) */
    private String relativePathRoot = "./";
    /** Instance that will temporary store the dependencies between tasks */
    private HashMap<String, ArrayList<String>> dependencies = null;

    /**
     * Create a new instance of StaxJobFactory.
     */
    StaxJobFactory() {
        System.setProperty("javax.xml.stream.XMLInputFactory", "com.ctc.wstx.stax.WstxInputFactory");
        //System.setProperty("javax.xml.stream.XMLOutputFactory", "com.ctc.wstx.stax.WstxOutputFactory");
        xmlif = XMLInputFactory.newInstance();
        xmlif.setProperty("javax.xml.stream.isCoalescing", Boolean.TRUE);
        xmlif.setProperty("javax.xml.stream.isReplacingEntityReferences", Boolean.TRUE);
        xmlif.setProperty("javax.xml.stream.isNamespaceAware", Boolean.TRUE);
        xmlif.setProperty("javax.xml.stream.supportDTD", Boolean.FALSE);
    }

    @Override
    public Job createJob(String filePath) throws JobCreationException {
        return createJob(filePath, null);
    }

    @Override
    public Job createJob(String filePath, Map<String, String> updatedVariables) throws JobCreationException {
        clean();
        try {
            // Check if the file exist
            File f = new File(filePath);
            return createJob(f, updatedVariables);
        } catch (JobCreationException jce) {
            throw jce;
        } catch (Exception e) {
            throw new JobCreationException(e);
        }
    }

    @Override
    public Job createJob(URI filePath) throws JobCreationException {
        return createJob(filePath, null);
    }

    @Override
    public Job createJob(URI filePath, Map<String,String> updatedVariables) throws JobCreationException {
        clean();
        try {
            //Check if the file exist
            File f = new File(filePath);
            return createJob(f, updatedVariables);
        } catch (JobCreationException jce) {
            throw jce;
        } catch (Exception e) {
            throw new JobCreationException(e);
        }
    }

    private Job createJob(File f, Map<String, String> updatedVariables) throws JobCreationException {
        try {
            //Check if the file exist
            if (!f.exists()) {
                throw new FileNotFoundException("This file has not been found : " + f.getAbsolutePath());
            }
            //validate content using the proper XML schema
            validate(f);
            //set relative path
            relativePathRoot = f.getParentFile().getAbsolutePath();
            //create and get XML STAX reader
            XMLStreamReader xmlsr;
            // use the server side property to accept encoding
            if (PASchedulerProperties.SCHEDULER_JOB_FILE_ENCODING.isSet()) {
                xmlsr = xmlif.createXMLStreamReader(new FileInputStream(f),
                        PASchedulerProperties.SCHEDULER_JOB_FILE_ENCODING.getValueAsString());
            } else {
                xmlsr = xmlif.createXMLStreamReader(new FileInputStream(f));
            }
            //Create the job starting at the first cursor position of the XML Stream reader
            createJob(xmlsr, updatedVariables);
            //Close the stream
            xmlsr.close();
            //make dependencies
            makeDependences();
            logger.debug("Job successfully created !");
            //debug mode only
            displayJobInfo();
            return job;
        } catch (JobCreationException jce) {
            jce.pushTag(XMLTags.JOB.getXMLName());
            throw jce;
        } catch (SAXException e) {
            throw new JobCreationException(true, e);
        } catch (Exception e) {
            throw new JobCreationException(e);
        }
    }

    private void clean() {
        this.variables = new HashMap<>();
        this.job = null;
        this.dependencies = null;
    }

    /*
     * Validate the given job descriptor
     */
    private void validate(File file) throws XMLStreamException, JobCreationException,
            VerifierConfigurationException, SAXException, IOException {
        String findSchemaByNamespaceUsed = findSchemaByNamespaceUsed(file);
        InputStream schemaStream = this.getClass().getResourceAsStream(findSchemaByNamespaceUsed);
        ValidationUtil.validate(file, schemaStream);
    }

    private String findSchemaByNamespaceUsed(File file) throws FileNotFoundException, XMLStreamException {
        XMLStreamReader cursorRoot = xmlif.createXMLStreamReader(new FileInputStream(file));
        String current;
        try {
            int eventType;
            while (cursorRoot.hasNext()) {
                eventType = cursorRoot.next();
                if (eventType == XMLEvent.START_ELEMENT) {
                    current = cursorRoot.getLocalName();
                    if (XMLTags.JOB.matches(current)) {
                        String namespace = cursorRoot.getName().getNamespaceURI();
                        return Schemas.SCHEMAS_BY_NAMESPACE.get(namespace).location;
                    }
                }
            }
            return Schemas.SCHEMA_LATEST.location;
        } catch (Exception e) {
            return Schemas.SCHEMA_LATEST.location;
        } finally {
            if (cursorRoot != null) {
                cursorRoot.close();
            }
        }
    }

    /**
     * Start parsing and creating the job.
     *
     * @throws JobCreationException if an error occurred during job creation process.
     */
    private void createJob(XMLStreamReader cursorRoot, Map<String, String> updatedVariables)
            throws JobCreationException {

        String current = null;
        //start parsing
        try {
            int eventType;
            while (cursorRoot.hasNext()) {
                eventType = cursorRoot.next();
                if (eventType == XMLEvent.START_ELEMENT) {
                    current = cursorRoot.getLocalName();
                    if (XMLTags.JOB.matches(current)) {
                        //first tag of the job.
                        createAndFillJob(cursorRoot, updatedVariables);
                    } else if (XMLTags.TASK.matches(current)) {
                        //once here, the job instance has been created
                        fillJobWithTasks(cursorRoot);
                    }
                }
            }
            //as the job attributes are declared before variable evaluation,
            //replace variables in this attributes after job creation (after variables evaluation)
            job.setName(replace(job.getName()));
            job.setProjectName(replace(job.getProjectName()));
            resolveCleaningScripts((TaskFlowJob) job, job.getVariables());
        } catch (JobCreationException jce) {
            if (XMLTags.TASK.matches(current)) {
                jce.pushTag(XMLTags.TASKFLOW.getXMLName());
            }
            throw jce;
        } catch (Exception e) {
            throw new JobCreationException(current, null, e);
        }
    }

    /**
     * Create the real job and fill it with its property. Leave the method at
     * the first tag that define the real type of job.
     *
     * @param cursorJob
     *            the streamReader with the cursor on the job element.
     * @param updatedVariableMap
     *            map of variables which has precedence over those that defined
     *            in Job descriptor
     * @throws JobCreationException
     *             if an exception occurs during job creation.
     */
    private void createAndFillJob(XMLStreamReader cursorJob, Map<String, String> updatedVariableMap)
            throws JobCreationException {
        //create a job that will just temporary store the common properties of the job
        Job jtmp = new Job() {

            @Override
            public JobId getId() {
                throw new RuntimeException("Not Available !");
            }

            @Override
            public JobType getType() {
                throw new RuntimeException("Not Available !");
            }
        };
        //parse job attributes and fill the temporary one
        int attrLen = cursorJob.getAttributeCount();
        int i = 0;
        for (; i < attrLen; i++) {
            String attrName = cursorJob.getAttributeLocalName(i);
            if (XMLAttributes.COMMON_NAME.matches(attrName)) {
                jtmp.setName(cursorJob.getAttributeValue(i));
            } else if (XMLAttributes.JOB_PRIORITY.matches(attrName)) {
                jtmp.setPriority(JobPriority.findPriority(replace(cursorJob.getAttributeValue(i))));
            } else if (XMLAttributes.COMMON_CANCELJOBONERROR.matches(attrName)) {
                jtmp.setCancelJobOnError(Boolean.parseBoolean(replace(cursorJob.getAttributeValue(i))));
            } else if (XMLAttributes.COMMON_RESTARTTASKONERROR.matches(attrName)) {
                jtmp.setRestartTaskOnError(RestartMode.getMode(replace(cursorJob.getAttributeValue(i))));
            } else if (XMLAttributes.COMMON_MAXNUMBEROFEXECUTION.matches(attrName)) {
                jtmp.setMaxNumberOfExecution(Integer.parseInt(replace(cursorJob.getAttributeValue(i))));
            } else if (XMLAttributes.JOB_PROJECTNAME.matches(attrName)) {
                //don't replace() here it is done at the end of the job
                jtmp.setProjectName(cursorJob.getAttributeValue(i));
            }
        }
        //parse job elements and fill the temporary one
        try {
            int eventType;
            boolean continu = true;
            while (continu && cursorJob.hasNext()) {
                eventType = cursorJob.next();
                switch (eventType) {
                    case XMLEvent.START_ELEMENT:
                        String current = cursorJob.getLocalName();
                        if (XMLTags.VARIABLES.matches(current)) {
                            if (! (updatedVariableMap ==  null || updatedVariableMap.isEmpty())) {
                                updateVariables(updatedVariableMap);
                            }
                            createVariables(cursorJob);
                            if (! (updatedVariableMap ==  null || updatedVariableMap.isEmpty())) {
                                updateVariables(updatedVariableMap);
                            }
                        } else if (XMLTags.COMMON_GENERIC_INFORMATION.matches(current)) {
                            jtmp.setGenericInformations(getGenericInformations(cursorJob));
                        } else if (XMLTags.JOB_CLASSPATHES.matches(current)) {
                            logger.warn(
                                    "Element " + XMLTags.JOB_CLASSPATHES.getXMLName() +
                                            " is no longer supported. Please define a " +
                                            XMLTags.FORK_ENVIRONMENT.getXMLName() + " per task if needed.");
                        } else if (XMLTags.COMMON_DESCRIPTION.matches(current)) {
                            jtmp.setDescription(getDescription(cursorJob));
                        } else if (XMLTags.DS_INPUTSPACE.matches(current)) {
                            jtmp.setInputSpace(getIOSpace(cursorJob));
                        } else if (XMLTags.DS_OUTPUTSPACE.matches(current)) {
                            jtmp.setOutputSpace(getIOSpace(cursorJob));
                        } else if (XMLTags.DS_GLOBALSPACE.matches(current)) {
                            jtmp.setGlobalSpace(getIOSpace(cursorJob));
                        } else if (XMLTags.DS_USERSPACE.matches(current)) {
                            jtmp.setUserSpace(getIOSpace(cursorJob));
                        } else if (XMLTags.TASKFLOW.matches(current)) {
                            job = new TaskFlowJob();
                            continu = false;
                        }
                        break;
                }
            }
            //if this point is reached, fill the real job using the temporary one
            job.setDescription(jtmp.getDescription());
            job.setName(jtmp.getName());
            job.setPriority(jtmp.getPriority());
            job.setProjectName(jtmp.getProjectName());
            job.setCancelJobOnError(jtmp.isCancelJobOnError());
            job.setRestartTaskOnError(jtmp.getRestartTaskOnError());
            job.setMaxNumberOfExecution(jtmp.getMaxNumberOfExecution());
            job.setGenericInformations(jtmp.getGenericInformations());
            job.setInputSpace(jtmp.getInputSpace());
            job.setOutputSpace(jtmp.getOutputSpace());
            job.setGlobalSpace(jtmp.getGlobalSpace());
            job.setUserSpace(jtmp.getUserSpace());
            job.setVariables(this.variables);
        } catch (JobCreationException jce) {
            jce.pushTag(cursorJob.getLocalName());
            throw jce;
        } catch (Exception e) {
            String attrtmp = null;
            if (cursorJob.isStartElement() && cursorJob.getAttributeCount() > i) {
                attrtmp = cursorJob.getAttributeLocalName(i);
            }
            throw new JobCreationException(cursorJob.getLocalName(), attrtmp, e);
        }
    }

    /**
     * Create the map of variables found in this document.
     * Leave the method with the cursor at the end of 'ELEMENT_VARIABLES' tag
     *
     * @param cursorVariables the streamReader with the cursor on the 'ELEMENT_VARIABLES' tag.
     */
    private void createVariables(XMLStreamReader cursorVariables) throws JobCreationException {
        try {
            int eventType;
            while (cursorVariables.hasNext()) {
                eventType = cursorVariables.next();
                switch (eventType) {
                    case XMLEvent.START_ELEMENT:
                        if (XMLTags.VARIABLE.matches(cursorVariables.getLocalName())) {
                            variables.put(cursorVariables.getAttributeValue(0), replace(cursorVariables
                                    .getAttributeValue(1)));
                        }
                        break;
                    case XMLEvent.END_ELEMENT:
                        if (XMLTags.VARIABLES.matches(cursorVariables.getLocalName())) {
                            return;
                        }
                        break;
                }
            }
        } catch (JobCreationException jce) {
            jce.pushTag(cursorVariables.getLocalName());
            throw jce;
        } catch (Exception e) {
            String attrtmp = null;
            if (cursorVariables.isStartElement() && cursorVariables.getAttributeCount() == 1) {
                attrtmp = cursorVariables.getAttributeLocalName(0);
            }
            throw new JobCreationException(cursorVariables.getLocalName(), attrtmp, e);
        }
    }

    private void updateVariables(Map<String, String> updatedVariables) {
            this.variables.putAll(updatedVariables);
    }

    /**
     * Get the defined generic informations of the entity.
     * Leave the method at the end of 'ELEMENT_COMMON_GENERIC_INFORMATION' tag.
     *
     * @param cursorInfo the streamReader with the cursor on the 'ELEMENT_COMMON_GENERIC_INFORMATION' tag.
     * @return the list of generic information as a hashMap.
     */
    private HashMap<String, String> getGenericInformations(XMLStreamReader cursorInfo)
            throws JobCreationException {
        HashMap<String, String> infos = new HashMap<>();
        try {
            int eventType;
            while (cursorInfo.hasNext()) {
                eventType = cursorInfo.next();
                switch (eventType) {
                    case XMLEvent.START_ELEMENT:
                        if (XMLTags.COMMON_INFO.matches(cursorInfo.getLocalName())) {
                            infos.put(cursorInfo.getAttributeValue(0),
                              replace(cursorInfo.getAttributeValue(1)));
                        }
                        break;
                    case XMLEvent.END_ELEMENT:
                        if (XMLTags.COMMON_GENERIC_INFORMATION.matches(cursorInfo.getLocalName())) {
                            return infos;
                        }
                        break;
                }
            }
            return infos;
        } catch (JobCreationException jce) {
            jce.pushTag(cursorInfo.getLocalName());
            throw jce;
        } catch (Exception e) {
            String attrtmp = null;
            if (cursorInfo.isStartElement() && cursorInfo.getAttributeCount() == 1) {
                attrtmp = cursorInfo.getAttributeLocalName(0);
            }
            throw new JobCreationException(cursorInfo.getLocalName(), attrtmp, e);
        }
    }

    /**
     * Get the description of the entity.
     * Leave the method with the cursor at the end of 'ELEMENT_COMMON_DESCRIPTION' tag.
     *
     * @param cursorVariables the streamReader with the cursor on the 'ELEMENT_COMMON_DESCRIPTION' tag.
     * @return the description between the tags.
     */
    private String getDescription(XMLStreamReader cursorVariables) throws JobCreationException {
        try {
            String description = "";
            //if description tag exists, then we have a characters event next.
            int eventType = cursorVariables.next();
            if (eventType == XMLEvent.CHARACTERS) {
                description = replace(cursorVariables.getText());
            } else if (eventType == XMLEvent.END_ELEMENT) {
                return description;
            }
            //go to the description END_ELEMENT
            while (cursorVariables.next() != XMLEvent.END_ELEMENT)
                ;

            return description;
        } catch (JobCreationException jce) {
            throw jce;
        } catch (Exception e) {
            throw new JobCreationException((String) null, null, e);
        }
    }

    /**
     * Get the INPUT/OUTPUT space URL of the job.
     * Leave the method with the cursor at the end of 'ELEMENT_DS_INPUT/OUTPUTSPACE' tag.
     *
     * @param cursorVariables the streamReader with the cursor on the 'ELEMENT_DS_INPUT/OUTPUTSPACE' tag.
     * @return the INPUT/OUTPUT space URL of this tag.
     */
    private String getIOSpace(XMLStreamReader cursorVariables) throws JobCreationException {
        try {
            String url = replace(cursorVariables.getAttributeValue(0));
            //go to the END_ELEMENT
            while (cursorVariables.next() != XMLEvent.END_ELEMENT)
                ;
            return url;
        } catch (JobCreationException jce) {
            throw jce;
        } catch (Exception e) {
            String attrtmp = null;
            if (cursorVariables.isStartElement() && cursorVariables.getAttributeCount() == 1) {
                attrtmp = cursorVariables.getAttributeLocalName(0);
            }
            throw new JobCreationException((String) null, attrtmp, e);
        }
    }

    /**
     * Fill the created Job with coming tasks..
     * Leave the method with the cursor at the end of the file (nothing more has to be parsed).
     *
     * @param cursorTask the streamReader with the cursor on the first 'ELEMENT_TASK' tag.
     */
    private void fillJobWithTasks(XMLStreamReader cursorTask) throws JobCreationException {
        XMLTags current = null;
        try {
            int eventType = -1;
            while (cursorTask.hasNext()) {
                //if use to keep the cursor on the task tag for the first loop
                if (eventType == -1) {
                    eventType = cursorTask.getEventType();
                } else {
                    eventType = cursorTask.next();
                }
                if (eventType == XMLEvent.START_ELEMENT && XMLTags.TASK.matches(cursorTask.getLocalName())) {
                    Task t;
                    switch (job.getType()) {
                        case TASKSFLOW:
                            current = XMLTags.TASK;
                            //create new task
                            t = createTask(cursorTask);
                            //add task to the job
                            ((TaskFlowJob) job).addTask(t);
                            break;
                        case PARAMETER_SWEEPING:
                            current = XMLTags.TASK;
                            throw new RuntimeException("Job Parameter Sweeping is not yet implemented !");
                    }
                }
            }
        } catch (JobCreationException jce) {
            jce.pushTag(current);
            throw jce;
        } catch (Exception e) {
            throw new JobCreationException(current, null, e);
        }
    }

    /**
     * Fill the given task by the information that are at the given cursorTask.
     * Leave the method with the cursor at the end of 'ELEMENT_TASK' tag.
     *
     * @param cursorTask the streamReader with the cursor on the 'ELEMENT_TASK' tag.
     * @return The newly created task that can be any type.
     */
    private Task createTask(XMLStreamReader cursorTask) throws JobCreationException {
        int i = 0;
        XMLTags currentTag = null;
        String current = null;
        String taskName = null;
        try {
            Task toReturn = null;
            Task tmpTask = new Task() {};
            //parse job attributes and fill the temporary one
            int attrLen = cursorTask.getAttributeCount();
            for (i = 0; i < attrLen; i++) {
                String attrName = cursorTask.getAttributeLocalName(i);
                if (XMLAttributes.COMMON_NAME.matches(attrName)) {
                    tmpTask.setName(cursorTask.getAttributeValue(i));
                    taskName = cursorTask.getAttributeValue(i);
                } else if (XMLAttributes.TASK_NB_NODES.matches(attrName)) {
                    int numberOfNodesNeeded = Integer.parseInt(replace(cursorTask.getAttributeValue(i)));
                    tmpTask.setParallelEnvironment(new ParallelEnvironment(numberOfNodesNeeded));
                } else if (XMLAttributes.COMMON_CANCELJOBONERROR.matches(attrName)) {
                    tmpTask.setCancelJobOnError(Boolean
                            .parseBoolean(replace(cursorTask.getAttributeValue(i))));
                } else if (XMLAttributes.COMMON_RESTARTTASKONERROR.matches(attrName)) {
                    tmpTask.setRestartTaskOnError(RestartMode
                            .getMode(replace(cursorTask.getAttributeValue(i))));
                } else if (XMLAttributes.COMMON_MAXNUMBEROFEXECUTION.matches(attrName)) {
                    tmpTask.setMaxNumberOfExecution(Integer
                            .parseInt(replace(cursorTask.getAttributeValue(i))));
                } else if (XMLAttributes.TASK_PRECIOUSRESULT.matches(attrName)) {
                    tmpTask.setPreciousResult(Boolean.parseBoolean(replace(cursorTask.getAttributeValue(i))));
                } else if (XMLAttributes.TASK_PRECIOUSLOGS.matches(attrName)) {
                    tmpTask.setPreciousLogs(Boolean.parseBoolean(replace(cursorTask.getAttributeValue(i))));
                } else if (XMLAttributes.TASK_WALLTIME.matches(attrName)) {
                    tmpTask.setWallTime(Tools.formatDate(replace(cursorTask.getAttributeValue(i))));
                } else if (XMLAttributes.TASK_RUNASME.matches(attrName)) {
                    tmpTask.setRunAsMe(Boolean.parseBoolean(replace(cursorTask.getAttributeValue(i))));
                }
            }
            int eventType;
            boolean continu = true;
            while (continu && cursorTask.hasNext()) {
                eventType = cursorTask.next();
                switch (eventType) {
                    case XMLEvent.START_ELEMENT:
                        current = cursorTask.getLocalName();
                        currentTag = null;
                        if (XMLTags.COMMON_GENERIC_INFORMATION.matches(current)) {
                            tmpTask.setGenericInformations(getGenericInformations(cursorTask));
                        } else if (XMLTags.COMMON_DESCRIPTION.matches(current)) {
                            tmpTask.setDescription(getDescription(cursorTask));
                        } else if (XMLTags.DS_INPUTFILES.matches(current)) {
                            setIOFIles(cursorTask, XMLTags.DS_INPUTFILES.getXMLName(), tmpTask);
                        } else if (XMLTags.DS_OUTPUTFILES.matches(current)) {
                            setIOFIles(cursorTask, XMLTags.DS_OUTPUTFILES.getXMLName(), tmpTask);
                        } else if (XMLTags.PARALLEL_ENV.matches(current)) {
                            tmpTask.setParallelEnvironment(createParallelEnvironment(cursorTask));
                        } else if (XMLTags.SCRIPT_SELECTION.matches(current)) {
                            tmpTask.setSelectionScripts(createSelectionScript(cursorTask));
                        } else if (XMLTags.FORK_ENVIRONMENT.matches(current)) {
                            tmpTask.setForkEnvironment(createForkEnvironment(cursorTask));
                        } else if (XMLTags.SCRIPT_PRE.matches(current)) {
                            tmpTask.setPreScript(createScript(cursorTask));
                        } else if (XMLTags.SCRIPT_POST.matches(current)) {
                            tmpTask.setPostScript(createScript(cursorTask));
                        } else if (XMLTags.SCRIPT_CLEANING.matches(current)) {
                            tmpTask.setCleaningScript(createScript(cursorTask));
                        } else if (XMLTags.FLOW.matches(current)) {
                            tmpTask.setFlowScript(createControlFlowScript(cursorTask, tmpTask));
                        } else if (XMLTags.TASK_DEPENDENCES.matches(current)) {
                            currentTag = XMLTags.TASK_DEPENDENCES;
                            createdependences(cursorTask, tmpTask);
                        } else if (XMLTags.JAVA_EXECUTABLE.matches(current)) {
                            toReturn = new JavaTask();
                            setJavaExecutable((JavaTask) toReturn, cursorTask);
                        } else if (XMLTags.NATIVE_EXECUTABLE.matches(current)) {
                            toReturn = new NativeTask();
                            setNativeExecutable((NativeTask) toReturn, cursorTask);
                        } else if (XMLTags.SCRIPT_EXECUTABLE.matches(current)) {
                            toReturn = new ScriptTask();
                            ((ScriptTask) toReturn).setScript(new TaskScript(createScript(cursorTask)));
                        }
                        break;
                    case XMLEvent.END_ELEMENT:
                        current = cursorTask.getLocalName();
                        if (XMLTags.TASK.matches(cursorTask.getLocalName())) {
                            continu = false;
                        }
                        break;
                }
            }
            //fill the real task with common attribute if it is a new one
            autoCopyfields(CommonAttribute.class, tmpTask, toReturn);
            autoCopyfields(Task.class, tmpTask, toReturn);
            if(toReturn != null){
                //set the following properties only if it is needed.
                if (toReturn.getCancelJobOnErrorProperty().isSet()) {
                    toReturn.setCancelJobOnError(toReturn.isCancelJobOnError());
                }
                if (toReturn.getRestartTaskOnErrorProperty().isSet()) {
                    toReturn.setRestartTaskOnError(toReturn.getRestartTaskOnError());
                }
                if (toReturn.getMaxNumberOfExecutionProperty().isSet()) {
                    toReturn.setMaxNumberOfExecution(toReturn.getMaxNumberOfExecution());
                }
            }
            return toReturn;
        } catch (JobCreationException jce) {
            jce.setTaskName(taskName);
            if (currentTag != null) {
                jce.pushTag(currentTag);
            } else {
                jce.pushTag(current);
            }
            throw jce;
        } catch (Exception e) {
            String attrtmp = null;
            if (cursorTask.isStartElement() && cursorTask.getAttributeCount() > i) {
                attrtmp = cursorTask.getAttributeLocalName(i);
            }
            if (currentTag != null) {
                throw new JobCreationException(currentTag, attrtmp, e);
            } else {
                throw new JobCreationException(current, attrtmp, e);
            }
        }
    }

    /**
     * Create the list of includes/excludes pattern for the given INPUT/OUTPUT files
     * Leave the method with the cursor at the end of 'ELEMENT_DS_INPUT/OUTPUTFILES' tag.
     *
     * @param cursorTask the streamReader with the cursor on the 'ELEMENT_DS_INPUT/OUTPUTFILES' tag.
     * @param endTag the final tag for this tag : ELEMENT_DS_INPUTFILES or ELEMENT_DS_INPUTFILES
     * @param task the task in which to add the input/output files selector
     * @throws JobCreationException
     */
    private void setIOFIles(XMLStreamReader cursorTask, String endTag, Task task) throws JobCreationException {
        int i = 0;
        try {
            int eventType;
            boolean continu = true;
            while (continu && cursorTask.hasNext()) {
                eventType = cursorTask.next();
                switch (eventType) {
                    case XMLEvent.START_ELEMENT:
                        String current = cursorTask.getLocalName();
                        if (XMLTags.DS_FILES.matches(current)) {
                            int attrLen = cursorTask.getAttributeCount();
                            FileSelector selector = null;
                            String accessMode = null;
                            for (i = 0; i < attrLen; i++) {
                                String attrName = cursorTask.getAttributeLocalName(i);
                                if (XMLAttributes.DS_INCLUDES.matches(attrName)) {
                                    if (selector == null) {
                                        selector = new FileSelector();
                                    }
                                    selector.setIncludes(cursorTask.getAttributeValue(i));
                                } else if (XMLAttributes.DS_EXCLUDES.matches(attrName)) {
                                    if (selector == null) {
                                        selector = new FileSelector();
                                    }
                                    selector.setExcludes(replace(cursorTask.getAttributeValue(i)));
                                } else if (XMLAttributes.DS_ACCESSMODE.matches(attrName)) {
                                    accessMode = cursorTask.getAttributeValue(i);
                                }
                                if (selector != null && accessMode != null) {
                                    if (XMLTags.DS_INPUTFILES.matches(endTag)) {
                                        task.addInputFiles(selector, InputAccessMode
                                                .getAccessMode(accessMode));
                                    } else {
                                        task.addOutputFiles(selector, OutputAccessMode
                                                .getAccessMode(accessMode));
                                    }
                                }
                            }
                        }
                        break;
                    case XMLEvent.END_ELEMENT:
                        if (cursorTask.getLocalName().equals(endTag)) {
                            continu = false;
                        }
                        break;
                }
            }
        } catch (JobCreationException jce) {
            jce.pushTag(cursorTask.getLocalName());
            throw jce;
        } catch (Exception e) {
            String attrtmp = null;
            if (cursorTask.isStartElement() && cursorTask.getAttributeCount() > i) {
                attrtmp = cursorTask.getAttributeLocalName(i);
            }
            throw new JobCreationException(cursorTask.getLocalName(), attrtmp, e);
        }
    }

    /**
     * Add the dependencies to the current task.
     * Leave this method at the end of the 'ELEMENT_TASK_DEPENDENCES' tag.
     *
     * @param cursorDepends the streamReader with the cursor on the 'ELEMENT_TASK_DEPENDENCES' tag.
     * @param t the task on which to apply the dependencies.
     */
    private void createdependences(XMLStreamReader cursorDepends, Task t) throws JobCreationException {
        try {
            if (dependencies == null) {
                dependencies = new HashMap<>();
            }
            ArrayList<String> depends = new ArrayList<>(0);
            int eventType;
            while (cursorDepends.hasNext()) {
                eventType = cursorDepends.next();
                switch (eventType) {
                    case XMLEvent.START_ELEMENT:
                        if (XMLTags.TASK_DEPENDENCES_TASK.matches(cursorDepends.getLocalName())) {
                            depends.add(cursorDepends.getAttributeValue(0));
                        }
                        break;
                    case XMLEvent.END_ELEMENT:
                        if (XMLTags.TASK_DEPENDENCES.matches(cursorDepends.getLocalName())) {
                            dependencies.put(t.getName(), depends);
                            return;
                        }

                        break;
                }
            }
        } catch (Exception e) {
            String attrtmp = null;
            if (cursorDepends.isStartElement() && cursorDepends.getAttributeCount() == 1) {
                attrtmp = cursorDepends.getAttributeLocalName(0);
            }
            throw new JobCreationException(cursorDepends.getLocalName(), attrtmp, e);
        }
    }

    private FlowScript createControlFlowScript(XMLStreamReader cursorTask, Task tmpTask)
            throws JobCreationException {
        String type = null;
        String target = null;
        String targetElse = null;
        String targetJoin = null;
        int event = -1;

        for (int i = 0; i < cursorTask.getAttributeCount(); i++) {
            String attrName = cursorTask.getAttributeLocalName(i);
            if (XMLAttributes.FLOW_BLOCK.matches(attrName)) {
                tmpTask.setFlowBlock(FlowBlock.parse(replace(cursorTask.getAttributeValue(i))));
            }
        }

        // <control>  =>  <if> | <loop> | <replicate>
        try {
            while (cursorTask.hasNext()) {
                event = cursorTask.next();
                if (event == XMLEvent.START_ELEMENT) {
                    break;
                } else if (event == XMLEvent.END_ELEMENT && XMLTags.FLOW.matches(cursorTask.getLocalName())) {
                    return null;
                }
            }
        } catch (Exception e) {
            throw new JobCreationException(XMLTags.FLOW.getXMLName(), null, e);
        }
        if (event != XMLEvent.START_ELEMENT) {
            throw new JobCreationException(XMLTags.FLOW.getXMLName(), null, null);
        }

        String tag = null;

        // REPLICATE : no attribute
        if (XMLTags.FLOW_REPLICATE.matches(cursorTask.getLocalName())) {
            type = FlowActionType.REPLICATE.toString();
            tag = XMLTags.FLOW_REPLICATE.getXMLName();
        }
        // IF : attributes TARGET_IF and TARGET_ELSE and TARGET_JOIN
        else if (XMLTags.FLOW_IF.matches(cursorTask.getLocalName())) {
            type = FlowActionType.IF.toString();
            tag = XMLTags.FLOW_IF.getXMLName();
            for (int i = 0; i < cursorTask.getAttributeCount(); i++) {
                String attrName = cursorTask.getAttributeLocalName(i);
                if (XMLAttributes.FLOW_TARGET.matches(attrName)) {
                    target = cursorTask.getAttributeValue(i);
                } else if (XMLAttributes.FLOW_ELSE.matches(attrName)) {
                    targetElse = cursorTask.getAttributeValue(i);
                } else if (XMLAttributes.FLOW_CONTINUATION.matches(attrName)) {
                    targetJoin = cursorTask.getAttributeValue(i);
                }
            }
        }
        // LOOP : attribute TARGET
        else if (XMLTags.FLOW_LOOP.matches(cursorTask.getLocalName())) {
            type = FlowActionType.LOOP.toString();
            tag = XMLTags.FLOW_LOOP.getXMLName();
            for (int i = 0; i < cursorTask.getAttributeCount(); i++) {
                String attrName = cursorTask.getAttributeLocalName(i);
                if (XMLAttributes.FLOW_TARGET.matches(attrName)) {
                    target = cursorTask.getAttributeValue(i);
                }
            }
        }
        FlowScript sc = null;
        Script<?> internalScript;
        try {
            internalScript = createScript(cursorTask, 2);
            switch (FlowActionType.parse(type)) {
                case IF:
                    sc = FlowScript.createIfFlowScript(internalScript, target, targetElse, targetJoin);
                    break;
                case REPLICATE:
                    sc = FlowScript.createReplicateFlowScript(internalScript);
                    break;
                case LOOP:
                    sc = FlowScript.createLoopFlowScript(internalScript, target);
                    break;
            }
        } catch (Exception e) {
            throw new JobCreationException(tag, null, e);
        }

        // </script>  -->  </if> | </replicate> | </loop>
        try {
            while (cursorTask.hasNext()) {
                event = cursorTask.next();
                if (event == XMLEvent.END_ELEMENT) {
                    break;
                }
            }
        } catch (XMLStreamException e) {
            throw new JobCreationException(tag, null, e);
        }
        if (event != XMLEvent.END_ELEMENT) {
            throw new JobCreationException(tag, null, null);
        }

        return sc;
    }

    /**
     * Creates the parallel environment from the xml descriptor.
     */
    private ParallelEnvironment createParallelEnvironment(XMLStreamReader cursorTask)
            throws JobCreationException {
        int event = -1;
        int nodesNumber = 0;
        TopologyDescriptor topologyDescriptor = null;

        // parallelEnvironment -> <topology>
        try {
            // cursor is parallelEnvironment
            for (int i = 0; i < cursorTask.getAttributeCount(); i++) {
                String attrName = cursorTask.getAttributeLocalName(i);
                if (XMLAttributes.TASK_NB_NODES.matches(attrName)) {
                    String value = replace(cursorTask.getAttributeValue(i));
                    nodesNumber = Integer.parseInt(value);
                }
            }

            while (cursorTask.hasNext()) {
                event = cursorTask.next();
                if (event == XMLEvent.START_ELEMENT) {
                    break;
                } else if (event == XMLEvent.END_ELEMENT &&
                    XMLTags.PARALLEL_ENV.matches(cursorTask.getLocalName())) {
                    return new ParallelEnvironment(nodesNumber, TopologyDescriptor.ARBITRARY);
                }
            }

            if (XMLTags.TOPOLOGY.matches(cursorTask.getLocalName())) {
                // topology element found
                while (cursorTask.hasNext()) {
                    event = cursorTask.next();
                    if (event == XMLEvent.START_ELEMENT) {
                        break;
                    } else if (event == XMLEvent.END_ELEMENT &&
                        XMLTags.TOPOLOGY.matches(cursorTask.getLocalName())) {
                        throw new RuntimeException("Incorrect topology description");
                    }
                }

                // arbitrary : no attributes
                if (XMLTags.TOPOLOGY_ARBITRARY.matches(cursorTask.getLocalName())) {
                    topologyDescriptor = TopologyDescriptor.ARBITRARY;
                }
                // bestProximity : no attributes
                else if (XMLTags.TOPOLOGY_BEST_PROXIMITY.matches(cursorTask.getLocalName())) {
                    topologyDescriptor = TopologyDescriptor.BEST_PROXIMITY;
                }
                // thresholdProximity : elements threshold
                else if (XMLTags.TOPOLOGY_THRESHOLD_PROXIMITY.matches(cursorTask.getLocalName())) {
                    // attribute threshold
                    for (int i = 0; i < cursorTask.getAttributeCount(); i++) {
                        String attrName = cursorTask.getAttributeLocalName(i);
                        if (XMLAttributes.TOPOLOGY_THRESHOLD.matches(attrName)) {
                            String value = replace(cursorTask.getAttributeValue(i));
                            long threshold = Long.parseLong(value);
                            topologyDescriptor = new ThresholdProximityDescriptor(threshold);
                        }
                    }
                }
                // singleHost : no attributes
                else if (XMLTags.TOPOLOGY_SINGLE_HOST.matches(cursorTask.getLocalName())) {
                    topologyDescriptor = TopologyDescriptor.SINGLE_HOST;
                }
                // singleHostExclusive : no attributes
                else if (XMLTags.TOPOLOGY_SINGLE_HOST_EXCLUSIVE.matches(cursorTask.getLocalName())) {
                    topologyDescriptor = TopologyDescriptor.SINGLE_HOST_EXCLUSIVE;
                }
                // multipleHostsExclusive : no attributes
                else if (XMLTags.TOPOLOGY_MULTIPLE_HOSTS_EXCLUSIVE.matches(cursorTask.getLocalName())) {
                    topologyDescriptor = TopologyDescriptor.MULTIPLE_HOSTS_EXCLUSIVE;
                }
                // oneNodePerHostHostsExclusive : no attributes
                else if (XMLTags.TOPOLOGY_DIFFERENT_HOSTS_EXCLUSIVE.matches(cursorTask.getLocalName())) {
                    topologyDescriptor = TopologyDescriptor.DIFFERENT_HOSTS_EXCLUSIVE;
                }
            }

        } catch (Exception e) {
            throw new JobCreationException(XMLTags.TOPOLOGY.getXMLName(), null, e);
        }

        return new ParallelEnvironment(nodesNumber, topologyDescriptor);
    }

    /**
     * Get the script defined at the specified cursor.
     * Leave the method with cursor at the end of the corresponding script.
     *
     * @param cursorScript the streamReader with the cursor on the corresponding script tag (pre, post, cleaning, selection, generation).
     * @param type nature of the script : 1 : selection
     *                                         2 : flow
     *                                         3 : else
     * @return the script defined at the specified cursor.
     */
    private Script<?> createScript(XMLStreamReader cursorScript, int type) throws JobCreationException {
        String attrtmp = null;
        String currentScriptTag = cursorScript.getLocalName();
        String current = null;
        try {
            boolean isDynamic = true;
            Script<?> toReturn = null;
            int eventType = -1;
            while (cursorScript.hasNext()) {
                if (type == 1 && eventType == -1) {
                    eventType = cursorScript.getEventType();
                } else {
                    eventType = cursorScript.next();
                }
                switch (eventType) {
                    case XMLEvent.START_ELEMENT:
                        current = cursorScript.getLocalName();
                        if (XMLTags.SCRIPT_CODE.matches(current)) {
                            String language = null;
                            String content = "";
                            if (cursorScript.getAttributeCount() > 0) {
                                language = cursorScript.getAttributeValue(0);
                                attrtmp = cursorScript.getAttributeLocalName(0);
                            }
                            //goto script content
                            if (cursorScript.next() == XMLEvent.CHARACTERS) {
                                content = cursorScript.getText();
                            }
                            toReturn = new SimpleScript(content, language);
                        } else if (XMLTags.SCRIPT_FILE.matches(current)) {
                            String path = null;
                            String url = null;
                            if (XMLAttributes.SCRIPT_URL.matches(cursorScript.getAttributeLocalName(0))) {
                                url = replace(cursorScript.getAttributeValue(0));
                            } else {
                                path = checkPath(cursorScript.getAttributeValue(0));
                            }
                            attrtmp = cursorScript.getAttributeLocalName(0);

                            //go to the next 'arguments' start element or the 'file' end element
                            while (true) {
                                int ev = cursorScript.next();
                                if (((ev == XMLEvent.START_ELEMENT) && XMLTags.SCRIPT_ARGUMENTS
                                        .matches(cursorScript.getLocalName())) ||
                                    (ev == XMLEvent.END_ELEMENT)) {
                                    break;
                                }
                            }

                            if (url != null) {
                                toReturn = new SimpleScript(new URL(url), getArguments(cursorScript));
                            } else {
                                toReturn = new SimpleScript(new File(path), getArguments(cursorScript));
                            }
                        } else if (XMLTags.SCRIPT_SCRIPT.matches(current)) {
                            if (cursorScript.getAttributeCount() > 0) {
                                isDynamic = !"static".equals(cursorScript.getAttributeValue(0));
                            }
                        }
                        break;
                    case XMLEvent.END_ELEMENT:
                        if (cursorScript.getLocalName().equals(currentScriptTag)) {
                            if (type == 1) {
                                return new SelectionScript(toReturn, isDynamic);
                            } else {
                                return toReturn;
                            }
                        }
                        break;
                }
            }
            return toReturn;
        } catch (JobCreationException jce) {
            jce.pushTag(current);
            throw jce;
        } catch (Exception e) {
            throw new JobCreationException(current, attrtmp, e);
        }
    }

    /**
     * Get the selection script defined at the specified cursor.
     * Leave the method with cursor at the end of the 'ELEMENT_SCRIPT_SELECTION' script.
     *
     * @param cursorScript the streamReader with the cursor on the 'ELEMENT_SCRIPT_SELECTION' tag.
     * @return the script defined at the specified cursor.
     */
    private List<SelectionScript> createSelectionScript(XMLStreamReader cursorScript)
            throws JobCreationException {
        List<SelectionScript> scripts = new ArrayList<>(0);
        String selectionTag = cursorScript.getLocalName();
        String current = null;
        try {
            SelectionScript newOne;
            int eventType;
            while (cursorScript.hasNext()) {
                eventType = cursorScript.next();
                switch (eventType) {
                    case XMLEvent.START_ELEMENT:
                        current = cursorScript.getLocalName();
                        if (XMLTags.SCRIPT_SCRIPT.matches(current)) {
                            newOne = (SelectionScript) createScript(cursorScript, 1);
                            scripts.add(newOne);
                        }
                        break;
                    case XMLEvent.END_ELEMENT:
                        current = cursorScript.getLocalName();
                        if (current.equals(selectionTag)) {
                            if (scripts.size() == 0) {
                                return null;
                            } else {
                                return scripts;
                            }
                        }
                        break;
                }
            }
        } catch (JobCreationException jce) {
            jce.pushTag(current);
            throw jce;
        } catch (Exception e) {
            throw new JobCreationException(current, null, e);
        }
        return scripts;
    }

    /**
     * Get the script defined at the specified cursor.
     * Leave the method with cursor at the end of the corresponding script.
     *
     * @param cursorScript the streamReader with the cursor on the corresponding script tag (env, pre, post, cleaning, generation).
     * @return the script defined at the specified cursor.
     */
    private Script<?> createScript(XMLStreamReader cursorScript) throws JobCreationException {
        try {
            return createScript(cursorScript, 0);
        } catch (JobCreationException jce) {
            jce.pushTag(XMLTags.SCRIPT_SCRIPT.getXMLName());
            throw jce;
        }
    }

    /**
     * Get the arguments at the given tag and return them as a string array.
     * Leave the cursor on the end 'ELEMENT_SCRIPT_ARGUMENTS' tag.
     *
     * @param cursorArgs the streamReader with the cursor on the 'ELEMENT_SCRIPT_ARGUMENTS' tag.
     * @return the arguments as a string array, null if no args.
     */
    private String[] getArguments(XMLStreamReader cursorArgs) throws JobCreationException {
        if (XMLTags.SCRIPT_ARGUMENTS.matches(cursorArgs.getLocalName())) {
            ArrayList<String> args = new ArrayList<>(0);
            try {
                int eventType;
                while (cursorArgs.hasNext()) {
                    eventType = cursorArgs.next();
                    switch (eventType) {
                        case XMLEvent.START_ELEMENT:
                            if (XMLTags.SCRIPT_ARGUMENT.matches(cursorArgs.getLocalName())) {
                                args.add(cursorArgs.getAttributeValue(0));
                            }
                            break;
                        case XMLEvent.END_ELEMENT:
                            if (XMLTags.SCRIPT_ARGUMENTS.matches(cursorArgs.getLocalName())) {
                                return args.toArray(new String[args.size()]);
                            }
                            break;
                    }
                }
                return args.toArray(new String[args.size()]);
            } catch (Exception e) {
                String attrtmp = null;
                if (cursorArgs.isStartElement() && cursorArgs.getAttributeCount() == 1) {
                    attrtmp = cursorArgs.getAttributeLocalName(0);
                }
                throw new JobCreationException(cursorArgs.getLocalName(), attrtmp, e);
            }
        } else {
            return null;
        }
    }

    /**
     * Add the Native Executable to this native Task.
     * The cursor is currently at the beginning of the 'ELEMENT_NATIVE_EXECUTABLE' tag.
     *
     * @param nativeTask the task in which to add the Native Executable.
     * @param cursorExec the streamReader with the cursor on the 'ELEMENT_NATIVE_EXECUTABLE' tag.
     */
    private void setNativeExecutable(NativeTask nativeTask, XMLStreamReader cursorExec)
            throws JobCreationException {
        int i = 0;
        String current = null;
        try {
            //one step ahead to go to the command (static or dynamic)
            while (cursorExec.next() != XMLEvent.START_ELEMENT)
                ;
            current = cursorExec.getLocalName();
            ArrayList<String> command = new ArrayList<>(0);
            if (XMLTags.NATIVE_TASK_STATICCOMMAND.matches(cursorExec.getLocalName())) {
                String attr_ = null;
                String current_ = null;
                try {
                    for (i = 0; i < cursorExec.getAttributeCount(); i++) {
                        String attrName = cursorExec.getAttributeLocalName(i);
                        attr_ = attrName;
                        if (XMLAttributes.TASK_COMMAND_VALUE.matches(attrName)) {
                            command.add((cursorExec.getAttributeValue(i)));
                        }
                        if (XMLAttributes.TASK_WORKDING_DIR.matches(attrName)) {
                            logger.warn(
                                    XMLAttributes.TASK_WORKDING_DIR.getXMLName()
                                            + " attribute no longer supported. Please use a forkEnvironment for defining a working directory.");
                        }
                    }

                    int eventType;
                    while (cursorExec.hasNext()) {
                        eventType = cursorExec.next();
                        switch (eventType) {
                            case XMLEvent.START_ELEMENT:
                                current_ = cursorExec.getLocalName();
                                if (XMLTags.SCRIPT_ARGUMENT.matches(cursorExec.getLocalName())) {
                                    command.add((cursorExec.getAttributeValue(0)));
                                }
                                break;
                            case XMLEvent.END_ELEMENT:
                                if (XMLTags.NATIVE_EXECUTABLE.matches(cursorExec.getLocalName())) {
                                    nativeTask.setCommandLine(command.toArray(new String[command.size()]));
                                    return;
                                }
                                break;
                        }
                    }
                } catch (Exception e) {
                    throw new JobCreationException(current_, attr_, e);
                }
            } else {
                throw new RuntimeException("Unknow command type : " + cursorExec.getLocalName());
            }
        } catch (JobCreationException jce) {
            jce.pushTag(current);
            throw jce;
        } catch (Exception e) {
            String attrtmp = null;
            if (cursorExec.isStartElement() && cursorExec.getAttributeCount() > 0) {
                attrtmp = cursorExec.getAttributeLocalName(i);
            }
            throw new JobCreationException(current, attrtmp, e);
        }
    }

    /**
     * Add the Java Executable to this java Task.
     * The cursor is currently at the beginning of the 'ELEMENT_JAVA_EXECUTABLE' tag.
     *
     * @param javaTask the task in which to add the Java Executable.
     * @param cursorExec the streamReader with the cursor on the 'ELEMENT_JAVA_EXECUTABLE' tag.
     */
    private void setJavaExecutable(JavaTask javaTask, XMLStreamReader cursorExec) throws JobCreationException {
        int i = 0;
        String current = cursorExec.getLocalName();
        try {
            //parsing executable attributes
            int attrCount = cursorExec.getAttributeCount();
            for (i = 0; i < attrCount; i++) {
                String attrName = cursorExec.getAttributeLocalName(i);
                if (XMLAttributes.TASK_CLASSNAME.matches(attrName)) {
                    javaTask.setExecutableClassName(cursorExec.getAttributeValue(i));
                }
            }
            //parsing executable tags
            int eventType;
            while (cursorExec.hasNext()) {
                eventType = cursorExec.next();
                switch (eventType) {
                    case XMLEvent.START_ELEMENT:
                        current = cursorExec.getLocalName();
                        if (XMLTags.FORK_ENVIRONMENT.matches(current)) {
                            ForkEnvironment forkEnv = createForkEnvironment(cursorExec);
                            javaTask.setForkEnvironment(forkEnv);
                        } else if (XMLTags.TASK_PARAMETER.matches(current)) {
                            javaTask.addArgument(replace(cursorExec.getAttributeValue(0)),
                              replace(cursorExec.getAttributeValue(1)));
                        }
                        break;
                    case XMLEvent.END_ELEMENT:
                        if (XMLTags.JAVA_EXECUTABLE.matches(cursorExec.getLocalName())) {
                            return;
                        }
                        break;
                }
            }
        } catch (JobCreationException jce) {
            jce.pushTag(current);
            throw jce;
        } catch (Exception e) {
            String attrtmp = null;
            if (cursorExec.isStartElement() && cursorExec.getAttributeCount() > 0) {
                attrtmp = cursorExec.getAttributeLocalName(i);
            }
            throw new JobCreationException(current, attrtmp, e);
        }
    }

    /**
     * Create the forkEnvironment of a java task
     * The cursor is currently at the beginning of the 'FORK_ENVIRONMENT' tag.
     *
     * @param cursorExec the streamReader with the cursor on the 'FORK_ENVIRONMENT' tag.
     * @return The created ForkEnvironment
     */
    private ForkEnvironment createForkEnvironment(XMLStreamReader cursorExec) throws JobCreationException {
        ForkEnvironment forkEnv = new ForkEnvironment();
        int i = 0;
        String current = cursorExec.getLocalName();
        try {
            //parsing executable attributes
            int attrCount = cursorExec.getAttributeCount();
            for (i = 0; i < attrCount; i++) {
                String attrName = cursorExec.getAttributeLocalName(i);
                if (XMLAttributes.FORK_JAVAHOME.matches(attrName)) {
                    forkEnv.setJavaHome(replace(cursorExec.getAttributeValue(i)));
                }
                if (XMLAttributes.TASK_WORKDING_DIR.matches(attrName)) {
                    forkEnv.setWorkingDir(replace(cursorExec.getAttributeValue(i)));
                }
            }
            //parsing executable tags
            int eventType;
            while (cursorExec.hasNext()) {
                eventType = cursorExec.next();
                switch (eventType) {
                    case XMLEvent.START_ELEMENT:
                        current = cursorExec.getLocalName();
                        if (XMLTags.FORK_SYSTEM_PROPERTY.matches(current)) {
                            attrCount = cursorExec.getAttributeCount();

                            String name = null, value = null;
                            for (i = 0; i < attrCount; i++) {
                                String attrName = cursorExec.getAttributeLocalName(i);
                                if (XMLAttributes.COMMON_NAME.matches(attrName)) {
                                    name = replace(cursorExec.getAttributeValue(i));
                                }
                                if (XMLAttributes.COMMON_VALUE.matches(attrName)) {
                                    value = replace(cursorExec.getAttributeValue(i));
                                }
                            }

                            forkEnv.addSystemEnvironmentVariable(name, value);
                        } else if (XMLTags.FORK_JVM_ARG.matches(current)) {
                            forkEnv.addJVMArgument(replace(cursorExec.getAttributeValue(0)));
                        } else if (XMLTags.JOB_PATH_ELEMENT.matches(current)) {
                            forkEnv.addAdditionalClasspath(replace(cursorExec.getAttributeValue(0)));
                        } else if (XMLTags.SCRIPT_ENV.matches(current)) {
                            forkEnv.setEnvScript(createScript(cursorExec));
                        }
                        break;
                    case XMLEvent.END_ELEMENT:
                        if (XMLTags.FORK_ENVIRONMENT.matches(cursorExec.getLocalName())) {
                            return forkEnv;
                        }
                        break;
                }
            }
            return forkEnv;
        } catch (JobCreationException jce) {
            jce.pushTag(current);
            throw jce;
        } catch (Exception e) {
            String attrtmp = null;
            if (cursorExec.isStartElement() && cursorExec.getAttributeCount() > 0) {
                attrtmp = cursorExec.getAttributeLocalName(i);
            }
            throw new JobCreationException(current, attrtmp, e);
        }
    }

    /**
     * Construct the dependencies between tasks.
     *
     * @throws JobCreationException if a dependencies name is unknown.
     */
    private void makeDependences() throws JobCreationException {
        if (dependencies != null && dependencies.size() > 0) {
            if (job.getType() == JobType.TASKSFLOW) {
                TaskFlowJob tfj = (TaskFlowJob) job;
                for (Task t : tfj.getTasks()) {
                    ArrayList<String> names = dependencies.get(t.getName());
                    if (names != null) {
                        for (String name : names) {
                            if (tfj.getTask(name) == null) {
                                throw new JobCreationException("Unknow dependence : " + name);
                            } else {
                                t.addDependence(tfj.getTask(name));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Replace the variables inside the given string by its value if needed.<br/>
     * This method looks for ${...} pattern and replace this pattern by the corresponding user variable
     * define in the 'ELEMENT_VARIABLES' tag.
     *
     * @param str the string in which to look for.
     * @return the string with variables replaced by values.
     * @throws JobCreationException if a Variable has not been found
     */
    private String replace(String str) throws JobCreationException {
        Map<String, String> replacements = new HashMap<>();
        for (Map.Entry o : System.getProperties().entrySet()) {
            replacements.put(o.getKey().toString(), o.getValue().toString());
        }
        replacements.putAll(this.variables);
        return filterAndUpdate(str, replacements);
    }

    /**
     * Replace the given file path by prepending relative root path if needed.<br/>
     * This method prepends the relative root path to the given path if it is not considered as an absolute path.
     *
     * @param path the path to be evaluated.
     * @return the same path with ${...} variables replaced and the relative path directory if this path was not absolute.
     * @throws JobCreationException if a Variable has not been found
     */
    private String checkPath(String path) throws JobCreationException {
        if (path == null || "".equals(path)) {
            return path;
        }
        //make variables replacement
        path = replace(path);
        //prepend if file is relative
        File f = new File(path);
        if (f.isAbsolute()) {
            return path;
        } else {
            return relativePathRoot + File.separator + path;
        }
    }

    private void displayJobInfo() {
        if (logger.isDebugEnabled()) {
            logger.debug("type : " + job.getType());
            logger.debug("name : " + job.getName());
            logger.debug("desc : " + job.getDescription());
            logger.debug("proj : " + job.getProjectName());
            logger.debug("prio : " + job.getPriority());
            logger.debug("cjoe : " + job.isCancelJobOnError());
            logger.debug("rtoe : " + job.getRestartTaskOnError());
            logger.debug("mnoe : " + job.getMaxNumberOfExecution());
            logger.debug("insp : " + job.getInputSpace());
            logger.debug("ousp : " + job.getOutputSpace());
            logger.debug("info : " + job.getGenericInformations());
            logger.debug("TASKS ------------------------------------------------");
            ArrayList<Task> tasks = new ArrayList<>();
            switch (job.getType()) {
                case TASKSFLOW:
                    tasks.addAll(((TaskFlowJob) job).getTasks());
                    break;
            }
            for (Task t : tasks) {
                logger.debug("name  : " + t.getName());
                logger.debug("desc  : " + t.getDescription());
                logger.debug("paral : " + t.isParallel());
                logger.debug("node  : " +
                    (t.getParallelEnvironment() == null ? "1" : t.getParallelEnvironment().getNodesNumber()));
                logger.debug("cjoe  : " + t.isCancelJobOnError());
                logger.debug("res   : " + t.isPreciousResult());
                logger.debug("logs  : " + t.isPreciousLogs());
                logger.debug("rtoe  : " + t.getRestartTaskOnError());
                logger.debug("mnoe  : " + t.getMaxNumberOfExecution());
                logger.debug("wall  : " + t.getWallTime());
                logger.debug("selec : " + t.getSelectionScripts());
                logger.debug("pre   : " + t.getPreScript());
                logger.debug("post  : " + t.getPostScript());
                logger.debug("clean : " + t.getCleaningScript());
                try {
                    logger.debug("ifsel : length=" + t.getInputFilesList().size());
                } catch (NullPointerException ignored) {
                }
                try {
                    logger.debug("ofsel : inc=" + t.getOutputFilesList().size());
                } catch (NullPointerException ignored) {
                }
                if (t.getDependencesList() != null) {
                    String dep = "dep   : ";
                    for (Task tdep : t.getDependencesList()) {
                        dep += tdep.getName() + " ";
                    }
                    logger.debug(dep);
                } else {
                    logger.debug("dep   : null");
                }
                logger.debug("infos : " + t.getGenericInformations());
                if (t instanceof JavaTask) {
                    logger.debug("class : " + ((JavaTask) t).getExecutableClassName());
                    try {
                        logger.debug("args  : " + ((JavaTask) t).getArguments());
                    } catch (Exception e) {
                        logger.debug("Cannot get args  : " + e.getMessage(), e);
                    }
                    logger.debug("fork  : " + ((JavaTask) t).isFork());
                } else if (t instanceof NativeTask) {
                    logger.debug("cmd   : " + Arrays.toString(((NativeTask) t).getCommandLine()));
                } else if (t instanceof ScriptTask) {
                    logger.debug("script   : " + ((ScriptTask) t).getScript());
                }

                ForkEnvironment forkEnvironment = t.getForkEnvironment();

                if (forkEnvironment != null) {
                    logger.debug("forkJ  : " + forkEnvironment.getJavaHome());
                    logger
                            .debug("forkSys: " +
                                    forkEnvironment.getSystemEnvironment());
                    logger.debug("forkJVM: " + forkEnvironment.getJVMArguments());
                    logger.debug("forkCP : " +
                            forkEnvironment.getAdditionalClasspath());
                    logger.debug("forkScr: " + forkEnvironment.getEnvScript());
                }

                logger.debug("--------------------------------------------------");
            }
        }
    }

    /**
     * Copy fields belonging to 'cFrom' from 'from' to 'to'.
     * Will only iterate on non-private field.
     * Private fields in 'cFrom' won't be set in 'to'.
     *
     * @param <T> check type given as argument is equals or under this type.
     * @param klass the klass in which to find the fields
     * @param from the T object in which to get the value
     * @param to the T object in which to set the value
     */
    private static <T> void autoCopyfields(Class<T> klass, T from, T to) throws IllegalArgumentException,
            IllegalAccessException {
        for (Field f : klass.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                Object newValue = f.get(from);
                if (newValue != null || f.get(to) == null) {
                    f.set(to, newValue);
                }
            }
        }
    }

    private static void resolveCleaningScripts(TaskFlowJob job, Map<String, String> variables) {
        for (Task task : job.getTasks()) {
            Script<?> cScript = task.getCleaningScript();
            if (cScript != null) {
                filterAndUpdate(cScript, variables);
            }
        }
    }
}
