// Copyright (c) 2018, 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.helpers;

import java.util.ArrayList;
import java.util.List;

import io.kubernetes.client.openapi.models.V1DeleteOptions;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobCondition;
import io.kubernetes.client.openapi.models.V1JobStatus;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import oracle.kubernetes.operator.DomainStatusUpdater;
import oracle.kubernetes.operator.IntrospectorConfigMapKeys;
import oracle.kubernetes.operator.JobWatcher;
import oracle.kubernetes.operator.LabelConstants;
import oracle.kubernetes.operator.ProcessingConstants;
import oracle.kubernetes.operator.TuningParameters;
import oracle.kubernetes.operator.calls.CallResponse;
import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;
import oracle.kubernetes.operator.logging.MessageKeys;
import oracle.kubernetes.operator.steps.DefaultResponseStep;
import oracle.kubernetes.operator.steps.ManagedServersUpStep;
import oracle.kubernetes.operator.steps.WatchDomainIntrospectorJobReadyStep;
import oracle.kubernetes.operator.wlsconfig.WlsDomainConfig;
import oracle.kubernetes.operator.work.NextAction;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;
import oracle.kubernetes.weblogic.domain.model.Cluster;
import oracle.kubernetes.weblogic.domain.model.ConfigurationConstants;
import oracle.kubernetes.weblogic.domain.model.Domain;
import oracle.kubernetes.weblogic.domain.model.DomainSpec;
import oracle.kubernetes.weblogic.domain.model.IntrospectorJobEnvVars;
import oracle.kubernetes.weblogic.domain.model.ManagedServer;
import oracle.kubernetes.weblogic.domain.model.ServerEnvVars;

import static oracle.kubernetes.operator.DomainSourceType.FromModel;
import static oracle.kubernetes.operator.logging.MessageKeys.INTROSPECTOR_JOB_FAILED;
import static oracle.kubernetes.operator.logging.MessageKeys.INTROSPECTOR_JOB_FAILED_DETAIL;

public class JobHelper {

  static final String START_TIME = "WlsRetriever-startTime";
  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");
  static final String INTROSPECTOR_LOG_PREFIX = "Introspector Job Log: ";
  private static final String EOL_PATTERN = "\\r?\\n";

  private JobHelper() {
  }

  static String createJobName(String domainUid) {
    return LegalNames.toJobIntrospectorName(domainUid);
  }

  /**
   * Factory for {@link Step} that creates WebLogic domain introspector job.
   * Uses the following packet values:
   *  ProcessingConstants.DOMAIN_TOPOLOGY - the domain topology
   *  ProcessingConstants.DOMAIN_RESTART_VERSION - the restart version from the domain
   *  ProcessingConstants.DOMAIN_INPUTS_HASH
   *  ProcessingConstants.DOMAIN_INTROSPECT_VERSION - the introspect version from the old domain spec
   *
   * @param next Next processing step
   * @return Step for creating job
   */
  public static Step createDomainIntrospectorJobStep(Step next) {

    return new DomainIntrospectorJobStep(next);
  }

  private static boolean runIntrospector(Packet packet, DomainPresenceInfo info) {
    WlsDomainConfig topology = (WlsDomainConfig) packet.get(ProcessingConstants.DOMAIN_TOPOLOGY);
    LOGGER.fine("runIntrospector topology: " + topology);
    LOGGER.fine("runningServersCount: " + runningServersCount(info));
    LOGGER.fine("creatingServers: " + creatingServers(info));
    return topology == null
          || isBringingUpNewDomain(info)
          || introspectionRequested(packet)
          || isModelInImageUpdate(packet, info);
  }

  private static boolean isBringingUpNewDomain(DomainPresenceInfo info) {
    return runningServersCount(info) == 0 && creatingServers(info);
  }

  private static boolean introspectionRequested(Packet packet) {
    return packet.containsKey(ProcessingConstants.DOMAIN_INTROSPECT_REQUESTED);
  }

  private static boolean isModelInImageUpdate(Packet packet, DomainPresenceInfo info) {
    if (info.getDomain().getDomainHomeSourceType() == FromModel) {

      final String currentPodRestartVersion = info.getDomain().getRestartVersion();
      final String configMapRestartVersion = (String) packet.get(IntrospectorConfigMapKeys.DOMAIN_RESTART_VERSION);
      final String configMapSpecHash = (String) packet.get(IntrospectorConfigMapKeys.DOMAIN_INPUTS_HASH);
      final String currentImageSpecHash = String.valueOf(ConfigMapHelper.getModelInImageSpecHash(info.getDomain()
          .getSpec().getImage()));

      LOGGER.finest("JobHelper.isModelInImageUpdate currentPodRestartVersion " + currentPodRestartVersion);
      LOGGER.finest("JobHelper.isModelInImageUpdate configMapRestartVersion " + configMapRestartVersion);

      // If either one is set, check for differences and decide to run intropsect job

      if (currentPodRestartVersion != null
            && !currentPodRestartVersion.equals(configMapRestartVersion)) {
        LOGGER.fine("JobHelper: currentPodRestartVersion version different from configmap");
        return true;
      }

      if (configMapRestartVersion != null
          && !configMapRestartVersion.equals(currentPodRestartVersion)) {
        LOGGER.fine("JobHelper: configMapRestartVersion version different from configmap");
        return true;
      }

      if (!currentImageSpecHash.equals(configMapSpecHash)) {
        LOGGER.fine("JobHelper: currentImageSpecHash version different from configmap");
        return true;
      }

    }
    return false;
  }

  private static int runningServersCount(DomainPresenceInfo info) {
    return ManagedServersUpStep.getRunningServers(info).size();
  }

  /**
   * TODO: Enhance determination of when we believe we're creating WLS managed server pods.
   *
   * @param info the domain presence info
   * @return True, if creating servers
   */
  static boolean creatingServers(DomainPresenceInfo info) {
    Domain dom = info.getDomain();
    DomainSpec spec = dom.getSpec();
    List<Cluster> clusters = spec.getClusters();
    List<ManagedServer> servers = spec.getManagedServers();

    // Are we starting a cluster?
    // NOTE: clusterServerStartPolicy == null indicates default policy
    for (Cluster cluster : clusters) {
      int replicaCount = dom.getReplicaCount(cluster.getClusterName());
      String clusterServerStartPolicy = cluster.getServerStartPolicy();
      LOGGER.fine(
            "Start Policy: "
                  + clusterServerStartPolicy
                  + ", replicaCount: "
                  + replicaCount
                  + " for cluster: "
                  + cluster);
      if ((clusterServerStartPolicy == null
            || !clusterServerStartPolicy.equals(ConfigurationConstants.START_NEVER))
            && replicaCount > 0) {
        return true;
      }
    }

    // If Domain level Server Start Policy = ALWAYS, IF_NEEDED or ADMIN_ONLY then we most likely
    // will start a server pod
    // NOTE: domainServerStartPolicy == null indicates default policy
    String domainServerStartPolicy = dom.getSpec().getServerStartPolicy();
    if (domainServerStartPolicy == null
          || !domainServerStartPolicy.equals(ConfigurationConstants.START_NEVER)) {
      return true;
    }

    // Are we starting any explicitly specified individual server?
    // NOTE: serverStartPolicy == null indicates default policy
    for (ManagedServer server : servers) {
      String serverStartPolicy = server.getServerStartPolicy();
      if (serverStartPolicy == null
            || !serverStartPolicy.equals(ConfigurationConstants.START_NEVER)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Factory for {@link Step} that deletes WebLogic domain introspector job.
   *
   * @param next Next processing step
   * @return Step for deleting the domain introsepctor jod
   */
  public static Step deleteDomainIntrospectorJobStep(Step next) {
    return new DeleteIntrospectorJobStep(next);
  }

  private static Step createWatchDomainIntrospectorJobReadyStep(Step next) {
    return new WatchDomainIntrospectorJobReadyStep(next);
  }

  /**
   * Factory for {@link Step} that reads WebLogic domain introspector job results from pod's log.
   *
   * @param next Next processing step
   * @return Step for reading WebLogic domain introspector pod log
   */
  private static Step readDomainIntrospectorPodLogStep(Step next) {
    return createWatchDomainIntrospectorJobReadyStep(
          readDomainIntrospectorPodStep(readDomainIntrospectorPodLog(next)));
  }

  /**
   * Factory for {@link Step} that reads WebLogic domain introspector pod.
   *
   * @param next Next processing step
   * @return Step for reading WebLogic domain introspector pod
   */
  private static Step readDomainIntrospectorPodStep(Step next) {
    return new ReadDomainIntrospectorPodStep(next);
  }

  static class DomainIntrospectorJobStepContext extends JobStepContext {
    private final DomainPresenceInfo info;

    // domainTopology is null if this is 1st time we're running job for this domain
    private final WlsDomainConfig domainTopology;

    DomainIntrospectorJobStepContext(DomainPresenceInfo info, Packet packet) {
      super(packet);
      this.info = info;
      this.domainTopology = (WlsDomainConfig) packet.get(ProcessingConstants.DOMAIN_TOPOLOGY);
      init();
    }

    /**
     * Creates the specified new pod and performs any additional needed processing.
     *
     * @param next the next step to perform after the pod creation is complete.
     * @return a step to be scheduled.
     */
    @Override
    Step createNewJob(Step next) {
      return createJob(next);
    }

    @Override
    String getJobCreatedMessageKey() {
      return MessageKeys.JOB_CREATED;
    }

    @Override
    String getJobName() {
      return LegalNames.toJobIntrospectorName(getDomainUid());
    }

    @Override
    protected List<V1Volume> getAdditionalVolumes() {
      return getDomain().getSpec().getAdditionalVolumes();
    }

    @Override
    protected List<V1VolumeMount> getAdditionalVolumeMounts() {
      return getDomain().getSpec().getAdditionalVolumeMounts();
    }

    private String getAsName() {
      return domainTopology.getAdminServerName();
    }

    private Integer getAsPort() {
      return domainTopology
          .getServerConfig(getAsName())
          .getLocalAdminProtocolChannelPort();
    }

    private boolean isLocalAdminProtocolChannelSecure() {
      return domainTopology
          .getServerConfig(getAsName())
          .isLocalAdminProtocolChannelSecure();
    }

    private String getAsServiceName() {
      return LegalNames.toServerServiceName(getDomainUid(), getAsName());
    }

    @Override
    List<V1EnvVar> getConfiguredEnvVars(TuningParameters tuningParameters) {
      // Pod for introspector job would use same environment variables as for admin server
      List<V1EnvVar> vars =
            PodHelper.createCopy(getDomain().getAdminServerSpec().getEnvironmentVariables());

      addEnvVar(vars, ServerEnvVars.DOMAIN_UID, getDomainUid());
      addEnvVar(vars, ServerEnvVars.DOMAIN_HOME, getDomainHome());
      addEnvVar(vars, ServerEnvVars.NODEMGR_HOME, getNodeManagerHome());
      addEnvVar(vars, ServerEnvVars.LOG_HOME, getEffectiveLogHome());
      addEnvVar(vars, ServerEnvVars.SERVER_OUT_IN_POD_LOG, getIncludeServerOutInPodLog());
      addEnvVar(vars, ServerEnvVars.ACCESS_LOG_IN_LOG_HOME, getHttpAccessLogInLogHome());
      addEnvVar(vars, IntrospectorJobEnvVars.NAMESPACE, getNamespace());
      addEnvVar(vars, IntrospectorJobEnvVars.INTROSPECT_HOME, getIntrospectHome());
      addEnvVar(vars, IntrospectorJobEnvVars.CREDENTIALS_SECRET_NAME, getWebLogicCredentialsSecretName());
      addEnvVar(vars, IntrospectorJobEnvVars.OPSS_KEY_SECRET_NAME, getOpssWalletPasswordSecretName());
      addEnvVar(vars, IntrospectorJobEnvVars.OPSS_WALLETFILE_SECRET_NAME, getOpssWalletFileSecretName());
      addEnvVar(vars, IntrospectorJobEnvVars.RUNTIME_ENCRYPTION_SECRET_NAME, getRuntimeEncryptionSecretName());
      addEnvVar(vars, IntrospectorJobEnvVars.WDT_DOMAIN_TYPE, getWdtDomainType());
      addEnvVar(vars, IntrospectorJobEnvVars.DOMAIN_SOURCE_TYPE, getDomainHomeSourceType().toString());
      addEnvVar(vars, IntrospectorJobEnvVars.ISTIO_ENABLED, Boolean.toString(isIstioEnabled()));
      addEnvVar(vars, IntrospectorJobEnvVars.ISTIO_READINESS_PORT, Integer.toString(getIstioReadinessPort()));
      addEnvVar(vars, IntrospectorJobEnvVars.ISTIO_POD_NAMESPACE, getNamespace());

      String dataHome = getDataHome();
      if (dataHome != null && !dataHome.isEmpty()) {
        addEnvVar(vars, ServerEnvVars.DATA_HOME, dataHome);
      }

      if (domainTopology != null) {
        // The domainTopology != null when the job is rerun for the same domain. In which
        // case we should now know how to contact the admin server, the admin server may
        // already be running, and the job may want to contact the admin server.

        addEnvVar(vars, "ADMIN_NAME", getAsName());
        addEnvVar(vars, "ADMIN_PORT", getAsPort().toString());
        if (isLocalAdminProtocolChannelSecure()) {
          addEnvVar(vars, "ADMIN_PORT_SECURE", "true");
        }
        addEnvVar(vars, "AS_SERVICE_NAME", getAsServiceName());

        // TBD Tom Barnes, Johnny Shum
        //     Do we need to pass to the jobwhether the admin server (or any pods)
        //     are already running?
      }

      return vars;
    }
  }

  static class DomainIntrospectorJobStep extends Step {

    DomainIntrospectorJobStep(Step next) {
      super(next);
    }

    @Override
    public NextAction apply(Packet packet) {
      DomainPresenceInfo info = packet.getSpi(DomainPresenceInfo.class);
      if (runIntrospector(packet, info)) {
        JobStepContext context = new DomainIntrospectorJobStepContext(info, packet);

        packet.putIfAbsent(START_TIME, System.currentTimeMillis());

        return doNext(
              context.createNewJob(
                    readDomainIntrospectorPodLogStep(
                          deleteDomainIntrospectorJobStep(
                                ConfigMapHelper.createIntrospectorConfigMapStep(getNext())))),
              packet);
      }

      return doNext(DomainValidationSteps.createValidateDomainTopologyStep(getNext()), packet);
    }
  }

  private static class DeleteIntrospectorJobStep extends Step {

    DeleteIntrospectorJobStep(Step next) {
      super(next);
    }

    @Override
    public NextAction apply(Packet packet) {
      return doNext(deleteJob(packet, getNext()), packet);
    }

    String getJobDeletedMessageKey() {
      return MessageKeys.JOB_DELETED;
    }

    void logJobDeleted(String domainUid, String namespace, String jobName, Packet packet) {
      V1Job domainIntrospectorJob =
          (V1Job) packet.remove(ProcessingConstants.DOMAIN_INTROSPECTOR_JOB);

      packet.remove(ProcessingConstants.INTROSPECTOR_JOB_FAILURE_LOGGED);
      if (domainIntrospectorJob != null
          && !JobWatcher.isComplete(domainIntrospectorJob)) {
        logIntrospectorFailure(packet, domainIntrospectorJob);
      }
      packet.remove(ProcessingConstants.JOB_POD_NAME);

      LOGGER.fine(getJobDeletedMessageKey(), domainUid, namespace, jobName);
    }

    private Step deleteJob(Packet packet, Step next) {
      DomainPresenceInfo info = packet.getSpi(DomainPresenceInfo.class);
      java.lang.String domainUid = info.getDomain().getDomainUid();
      java.lang.String namespace = info.getNamespace();
      String jobName = JobHelper.createJobName(domainUid);
      logJobDeleted(domainUid, namespace, jobName, packet);
      return new CallBuilder()
            .deleteJobAsync(
                  jobName,
                  namespace,
                  new V1DeleteOptions().propagationPolicy("Foreground"),
                  new DefaultResponseStep<>(next));
    }
  }

  static ReadDomainIntrospectorPodLogStep readDomainIntrospectorPodLog(Step next) {
    return new ReadDomainIntrospectorPodLogStep(next);
  }

  private static class ReadDomainIntrospectorPodLogStep extends Step {

    ReadDomainIntrospectorPodLogStep(Step next) {
      super(next);
    }

    @Override
    public NextAction apply(Packet packet) {
      DomainPresenceInfo info = packet.getSpi(DomainPresenceInfo.class);
      String namespace = info.getNamespace();

      String jobPodName = (String) packet.get(ProcessingConstants.JOB_POD_NAME);

      return doNext(readDomainIntrospectorPodLog(jobPodName, namespace, getNext()), packet);
    }

    private Step readDomainIntrospectorPodLog(String jobPodName, String namespace, Step next) {
      return new CallBuilder()
            .readPodLogAsync(
                  jobPodName, namespace, new ReadDomainIntrospectorPodLogResponseStep(next));
    }
  }

  private static class ReadDomainIntrospectorPodLogResponseStep extends ResponseStep<String> {
    private StringBuilder logMessage = new StringBuilder();
    private final List<String> severeStatuses = new ArrayList<>();

    ReadDomainIntrospectorPodLogResponseStep(Step nextStep) {
      super(nextStep);
    }

    @Override
    public NextAction onSuccess(Packet packet, CallResponse<String> callResponse) {
      String result = callResponse.getResult();
      LOGGER.fine("+++++ ReadDomainIntrospectorPodLogResponseStep: \n" + result);

      if (result != null) {
        convertJobLogsToOperatorLogs(result);
        if (!severeStatuses.isEmpty()) {
          updateStatus(packet.getSpi(DomainPresenceInfo.class));
        }
        packet.put(ProcessingConstants.DOMAIN_INTROSPECTOR_LOG_RESULT, result);
      }

      V1Job domainIntrospectorJob =
            (V1Job) packet.get(ProcessingConstants.DOMAIN_INTROSPECTOR_JOB);

      if (isNotComplete(domainIntrospectorJob)) {
        List<String> jobConditionsReason = new ArrayList<>();
        if (domainIntrospectorJob != null) {
          logIntrospectorFailure(packet, domainIntrospectorJob);
          V1JobStatus status = domainIntrospectorJob.getStatus();
          if (status != null && status.getConditions() != null) {
            for (V1JobCondition cond : status.getConditions()) {
              jobConditionsReason.add(cond.getReason());
            }
          }
        }
        if (jobConditionsReason.size() == 0) {
          jobConditionsReason.add(DomainStatusPatch.ERR_INTROSPECTOR);
        }
        //Introspector job is incomplete, update domain status and terminate processing
        return doNext(
            DomainStatusUpdater.createFailedStep(
              onSeparateLines(jobConditionsReason),
              onSeparateLines(severeStatuses),
                null),
            packet);
      }

      return doNext(packet);
    }

    private boolean isNotComplete(V1Job domainIntrospectorJob) {
      return domainIntrospectorJob == null || !JobWatcher.isComplete(domainIntrospectorJob);
    }

    // Parse log messages out of a Job Log
    //  - assumes each job log message starts with '@['
    //  - assumes any lines that don't start with '@[' are part
    //    of the previous log message
    //  - ignores all lines in the log up to the first line that starts with '@['
    private void convertJobLogsToOperatorLogs(String jobLogs) {
      for (String line : jobLogs.split(EOL_PATTERN)) {
        if (line.startsWith("@[")) {
          logToOperator();
          logMessage = new StringBuilder(INTROSPECTOR_LOG_PREFIX).append(line.trim());
        } else if (logMessage.length() > 0) {
          logMessage.append(System.lineSeparator()).append(line.trim());
        }
      }
      logToOperator();
    }

    private void logToOperator() {
      if (logMessage.length() == 0) {
        return;
      }

      String logMsg = logMessage.toString();
      switch (getLogLevel(logMsg)) {
        case "SEVERE":
          addSevereStatus(logMsg); // fall through
        case "ERROR":
          LOGGER.severe(logMsg);
          break;
        case "WARNING":
          LOGGER.warning(logMsg);
          break;
        case "INFO":
          LOGGER.info(logMsg);
          break;
        case "FINER":
          LOGGER.finer(logMsg);
          break;
        case "FINEST":
          LOGGER.finest(logMsg);
          break;
        case "FINE":
        default:
          LOGGER.fine(logMsg);
          break;
      }
    }

    private void addSevereStatus(String logMsg) {
      int index = logMsg.toUpperCase().lastIndexOf("[SEVERE]") + "[SEVERE]".length();
      severeStatuses.add(logMsg.substring(index).trim());
    }

    private String getLogLevel(String logMsg) {
      String regExp = ".*\\[(SEVERE|ERROR|WARNING|INFO|FINE|FINER|FINEST)].*";
      return getFirstLine(logMsg).toUpperCase().replaceAll(regExp, "$1");
    }

    private String getFirstLine(String logMsg) {
      return logMsg.split(EOL_PATTERN)[0];
    }

    private void updateStatus(DomainPresenceInfo domainPresenceInfo) {
      DomainStatusPatch.updateSynchronously(
            domainPresenceInfo.getDomain(), DomainStatusPatch.ERR_INTROSPECTOR, onSeparateLines(severeStatuses));
    }

    private String onSeparateLines(List<String> lines) {
      return String.join(System.lineSeparator(), lines);
    }
  }

  private static void logIntrospectorFailure(Packet packet, V1Job domainIntrospectorJob) {
    Boolean logged = (Boolean) packet.get(ProcessingConstants.INTROSPECTOR_JOB_FAILURE_LOGGED);
    String jobPodName = (String) packet.get(ProcessingConstants.JOB_POD_NAME);
    if (logged == null || !logged.booleanValue()) {
      packet.put(ProcessingConstants.INTROSPECTOR_JOB_FAILURE_LOGGED, Boolean.valueOf(true));
      LOGGER.info(INTROSPECTOR_JOB_FAILED,
          domainIntrospectorJob.getMetadata().getNamespace(),
          domainIntrospectorJob.getMetadata().getName(),
          jobPodName,
          domainIntrospectorJob.getStatus());
      LOGGER.fine(INTROSPECTOR_JOB_FAILED_DETAIL,
          domainIntrospectorJob.getMetadata().getNamespace(),
          domainIntrospectorJob.getMetadata().getName(),
          domainIntrospectorJob.toString());
    }
  }

  private static class ReadDomainIntrospectorPodStep extends Step {

    ReadDomainIntrospectorPodStep(Step next) {
      super(next);
    }

    @Override
    public NextAction apply(Packet packet) {
      DomainPresenceInfo info = packet.getSpi(DomainPresenceInfo.class);
      String domainUid = info.getDomain().getDomainUid();
      String namespace = info.getNamespace();

      return doNext(readDomainIntrospectorPod(domainUid, namespace, getNext()), packet);
    }

    private Step readDomainIntrospectorPod(String domainUid, String namespace, Step next) {
      return new CallBuilder()
            .withLabelSelectors(LabelConstants.JOBNAME_LABEL)
            .listPodAsync(namespace, new PodListStep(domainUid, next));
    }
  }

  private static class PodListStep extends ResponseStep<V1PodList> {
    private final String domainUid;

    PodListStep(String domainUid, Step next) {
      super(next);
      this.domainUid = domainUid;
    }

    @Override
    public NextAction onFailure(Packet packet, CallResponse<V1PodList> callResponse) {
      return super.onFailure(packet, callResponse);
    }

    @Override
    public NextAction onSuccess(Packet packet, CallResponse<V1PodList> callResponse) {
      String jobNamePrefix = createJobName(domainUid);
      V1PodList result = callResponse.getResult();
      if (result != null) {
        for (V1Pod pod : result.getItems()) {
          if (pod.getMetadata().getName().startsWith(jobNamePrefix)) {
            LOGGER.fine("+++++ JobHelper.PodListStep pod: " + pod.toString());
            packet.put(ProcessingConstants.JOB_POD_NAME, pod.getMetadata().getName());
          }
        }
      }

      return doNext(packet);
    }
  }
}
