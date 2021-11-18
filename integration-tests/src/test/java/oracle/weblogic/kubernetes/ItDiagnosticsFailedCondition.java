// Copyright (c) 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1LocalObjectReference;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimVolumeSource;
import io.kubernetes.client.openapi.models.V1SecretReference;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import oracle.weblogic.domain.AdminServer;
import oracle.weblogic.domain.AdminService;
import oracle.weblogic.domain.Channel;
import oracle.weblogic.domain.Cluster;
import oracle.weblogic.domain.Configuration;
import oracle.weblogic.domain.Domain;
import oracle.weblogic.domain.DomainSpec;
import oracle.weblogic.domain.Model;
import oracle.weblogic.domain.ServerPod;
import oracle.weblogic.domain.ServerService;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import oracle.weblogic.kubernetes.utils.FmwUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static oracle.weblogic.kubernetes.ItMiiDomainModelInPV.buildMIIandPushToRepo;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_PASSWORD_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_USERNAME_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.BASE_IMAGES_REPO_SECRET;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_API_VERSION;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_STATUS_CONDITION_AVAILABLE_TYPE;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_STATUS_CONDITION_COMPLETED_TYPE;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_STATUS_CONDITION_FAILED_TYPE;
import static oracle.weblogic.kubernetes.TestConstants.FMWINFRA_IMAGE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.FMWINFRA_IMAGE_TAG;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_APP_NAME;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_TAG;
import static oracle.weblogic.kubernetes.TestConstants.OCIR_REGISTRY;
import static oracle.weblogic.kubernetes.TestConstants.OCIR_SECRET_NAME;
import static oracle.weblogic.kubernetes.TestConstants.WEBLOGIC_IMAGE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.WEBLOGIC_IMAGE_TO_USE_IN_SPEC;
import static oracle.weblogic.kubernetes.actions.ActionConstants.MODEL_DIR;
import static oracle.weblogic.kubernetes.actions.TestActions.deleteConfigMap;
import static oracle.weblogic.kubernetes.actions.TestActions.deletePersistentVolume;
import static oracle.weblogic.kubernetes.actions.TestActions.deletePersistentVolumeClaim;
import static oracle.weblogic.kubernetes.utils.ConfigMapUtils.createConfigMapFromFiles;
import static oracle.weblogic.kubernetes.utils.DbUtils.createRcuAccessSecret;
import static oracle.weblogic.kubernetes.utils.DomainUtils.checkDomainStatusConditionTypeExists;
import static oracle.weblogic.kubernetes.utils.DomainUtils.checkDomainStatusConditionTypeHasExpectedStatus;
import static oracle.weblogic.kubernetes.utils.DomainUtils.createDomainAndVerify;
import static oracle.weblogic.kubernetes.utils.DomainUtils.deleteDomainResource;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createDockerRegistrySecret;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createMiiImageAndVerify;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createSecretForBaseImages;
import static oracle.weblogic.kubernetes.utils.ImageUtils.dockerLoginAndPushImageToRegistry;
import static oracle.weblogic.kubernetes.utils.OperatorUtils.installAndVerifyOperator;
import static oracle.weblogic.kubernetes.utils.PodUtils.setPodAntiAffinity;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createOpsswalletpasswordSecret;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests related to Domain status conditions logged by operator.
 * The tests checks for the Failed conditions for multiple usecases.
 */
@DisplayName("Verify the domain status failed conditions for domain lifecycle")
@IntegrationTest
class ItDiagnosticsFailedCondition {

  private static String domainNamespace = null;

  final String adminServerName = "admin-server";
  final String adminServerPodName = domainUid + "-" + adminServerName;
  final String managedServerNameBase = "ms-";
  final String cluster1Name = "mycluster";
  String managedServerPodNamePrefix = domainUid + "-" + managedServerNameBase;
  final int managedServerPort = 8001;
  int replicaCount = 2;

  private static String adminSecretName;
  private static String encryptionSecretName;

  private static final String domainUid = "diagnosticsdomain";
  private static final String pvName = domainUid + "-pv"; // name of the persistent volume
  private static final String pvcName = domainUid + "-pvc"; // name of the persistent volume claim
  private static String opServiceAccount = null;
  private static String opNamespace = null;

  private static LoggingFacade logger = null;

  /**
   * Assigns unique namespaces for operator and domains.
   * Pull WebLogic image if running tests in Kind cluster.
   * Installs operator.
   *
   * @param namespaces injected by JUnit
   */
  @BeforeAll
  public static void initAll(@Namespaces(2) List<String> namespaces) {
    logger = getLogger();
    logger.info("Assign a unique namespace for operator");
    assertNotNull(namespaces.get(0), "Namespace is null");
    opNamespace = namespaces.get(0);

    logger.info("Assign a unique namespace for WebLogic domain");
    assertNotNull(namespaces.get(1), "Namespace is null");
    domainNamespace = namespaces.get(1);

    // set the service account name for the operator
    opServiceAccount = opNamespace + "-sa";

    // install and verify operator with REST API
    installAndVerifyOperator(opNamespace, opServiceAccount, true, 0, domainNamespace);

    // create pull secrets for WebLogic image when running in non Kind Kubernetes cluster
    // this secret is used only for non-kind cluster
    createSecretForBaseImages(domainNamespace);

    // create secret for admin credentials
    logger.info("Create secret for admin credentials");
    adminSecretName = "weblogic-credentials";
    createSecretWithUsernamePassword(adminSecretName, domainNamespace,
            ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT);

    // create encryption secret
    logger.info("Create encryption secret");
    encryptionSecretName = "encryptionsecret";
    createSecretWithUsernamePassword(encryptionSecretName, domainNamespace,
            "weblogicenc", "weblogicenc");
  }

  /**
   * Test domain status condition with a bad model file.
   * Verify the following conditions are generated after a introspector failure.
   * type: Completed, status: true
   * type: Available, status: false
   * type: Failed, status: true
   */
  @Test
  @DisplayName("Test domain status condition with bad model file")
  void testBadModelFileStatus() {
    // build a build with empty WebLogic domain
    String imageName = MII_BASIC_IMAGE_NAME;
    String imageTag = "empty-domain-image";
    buildMIIandPushToRepo(imageName, imageTag, null);

    String badModelFileCm = "bad-model-in-cm";
    Path badModelFile = Paths.get(MODEL_DIR, "bad-model-file.yaml");
    final List<Path> modelList = Collections.singletonList(badModelFile);

    logger.info("creating a config map containing the bad model file");
    createConfigMapFromFiles(badModelFileCm, modelList, domainNamespace);

    try {
      // Test - test bad model file status with introspector failure
      logger.info("Creating a domain resource with bad model file from configmap");
      Domain domain = createDomainResourceWithConfigMap(domainUid, domainNamespace, adminSecretName,
          OCIR_SECRET_NAME, encryptionSecretName, replicaCount, imageName + ":" + imageTag, badModelFileCm, 30L);
      createDomainAndVerify(domain, domainNamespace);

      //check the desired completed, available and failed statuses
      checkStatus("True", "False", "True");

    } finally {
      deleteDomainResource(domainNamespace, domainUid);
      deleteConfigMap(badModelFileCm, domainNamespace);
    }
  }

  /**
   * Test domain status condition with replicas set to more than maximum size of the WebLogic cluster created.
   * generated: type: Completed, status: true type: Available, status: false type: Failed status: true
   */
  @Test
  @DisplayName("Test domain status condition with replicas set to more than available in cluster")
  void testReplicasTooHigh() {
    String image = MII_BASIC_IMAGE_NAME + ":" + MII_BASIC_IMAGE_TAG;

    logger.info("Creating domain resource with replicas=100");
    Domain domain = createDomainResource(domainUid, domainNamespace, adminSecretName,
        OCIR_SECRET_NAME, encryptionSecretName, 100, image);

    try {
      logger.info("Creating domain");
      createDomainAndVerify(domain, domainNamespace);

      //check the desired completed, available and failed statuses
      checkStatus("True", "False", "True");

    } finally {
      deleteDomainResource(domainNamespace, domainUid);
    }
  }

  /**
   * Test domain status condition with non-existing image. Verify the following conditions are
   * generated: type: Completed, status: true type: Available, status: false type: Failed status: true
   */
  @Test
  @DisplayName("Test domain status failed condition with non-existing image")
  void testImageDoesnotExist() {
    String image = MII_BASIC_IMAGE_NAME + ":non-existing";

    logger.info("Creating domain resource with non-existing image");
    Domain domain = createDomainResource(domainUid, domainNamespace, adminSecretName,
        OCIR_SECRET_NAME, encryptionSecretName, replicaCount, image);

    try {
      logger.info("Creating domain");
      createDomainAndVerify(domain, domainNamespace);

      //check the desired completed, available and failed statuses
      checkStatus("True", "False", "True");

    } finally {
      deleteDomainResource(domainNamespace, domainUid);
    }
  }

  /**
   * Test domain status failed condition with missing image pull secret. Verify the following conditions are
   * generated: type: Completed, status: true type: Available, status: false type: Failed status: true
   */
  @Test
  @DisplayName("Test domain status condition with missing image pull secret")
  void testImagePullSecretDoesnotExist() {
    String image = MII_BASIC_IMAGE_NAME + ":" + MII_BASIC_IMAGE_TAG;

    logger.info("Creating domain resource with missing image pull secret");
    Domain domain = createDomainResource(domainUid, domainNamespace, adminSecretName,
        OCIR_SECRET_NAME + "bad", encryptionSecretName, 100, image);

    try {
      logger.info("Creating domain");
      createDomainAndVerify(domain, domainNamespace);

      //check the desired completed, available and failed statuses
      checkStatus("True", "False", "True");

    } finally {
      deleteDomainResource(domainNamespace, domainUid);
    }
  }

  /**
   * Test domain status failed condition with incorrect image pull secret. Verify the following conditions are
   * generated: type: Completed, status: true type: Available, status: false type: Failed status: true
   */
  @Test
  @DisplayName("Test domain status condition with incorrect image pull secret")
  void testIncorrectImagePullSecret() {
    String image = WEBLOGIC_IMAGE_NAME + ":nonexistent";
    logger.info("Creating a docker secret with invalid credentials");
    createDockerRegistrySecret("foo", "bar", "foo@bar.com", OCIR_REGISTRY,
        "bad-pull-secret", domainNamespace);

    logger.info("Creating domain resource with incorrect image pull secret");
    Domain domain = createDomainResource(domainUid, domainNamespace, adminSecretName,
        "bad-pull-secret", encryptionSecretName, replicaCount, image);
    domain.getSpec().imagePullPolicy("Always");

    try {
      logger.info("Creating domain");
      createDomainAndVerify(domain, domainNamespace);

      //check the desired completed, available and failed statuses
      checkStatus("True", "False", "True");

    } finally {
      deleteDomainResource(domainNamespace, domainUid);
    }
  }

  /**
   * Test domain status failed condition with non-existing persistent volume. Verify the following conditions are
   * generated: type: Completed, status: true type: Available, status: false type: Failed status: true
   */
  @Test
  @DisplayName("Test domain status condition with non-existent pv")
  void testNonexistentPVC() {
    try {
      // create a domain custom resource configuration object
      logger.info("Creating domain custom resource");
      Domain domain = new Domain()
          .apiVersion(DOMAIN_API_VERSION)
          .kind("Domain")
          .metadata(new V1ObjectMeta()
              .name(domainUid)
              .namespace(domainNamespace))
          .spec(new DomainSpec()
              .domainUid(domainUid)
              .domainHome("/shared/domains/" + domainUid) // point to domain home in pv
              .domainHomeSourceType("PersistentVolume") // set the domain home source type as pv
              .image(WEBLOGIC_IMAGE_TO_USE_IN_SPEC)
              .imagePullPolicy("IfNotPresent")
              .imagePullSecrets(Arrays.asList(
                  new V1LocalObjectReference()
                      .name(BASE_IMAGES_REPO_SECRET))) // this secret is used only in non-kind cluster
              .webLogicCredentialsSecret(new V1SecretReference()
                  .name(adminSecretName)
                  .namespace(domainNamespace))
              .includeServerOutInPodLog(true)
              .logHomeEnabled(Boolean.TRUE)
              .logHome("/shared/logs/" + domainUid)
              .dataHome("")
              .serverStartPolicy("IF_NEEDED")
              .serverPod(new ServerPod() //serverpod
                  .addEnvItem(new V1EnvVar()
                      .name("USER_MEM_ARGS")
                      .value("-Djava.security.egd=file:/dev/./urandom "))
                  .addVolumesItem(new V1Volume()
                      .name(pvName)
                      .persistentVolumeClaim(new V1PersistentVolumeClaimVolumeSource()
                          .claimName(pvcName)))
                  .addVolumeMountsItem(new V1VolumeMount()
                      .mountPath("/shared")
                      .name(pvName)))
              .adminServer(new AdminServer() //admin server
                  .serverStartState("RUNNING")
                  .adminService(new AdminService()
                      .addChannelsItem(new Channel()
                          .channelName("default")
                          .nodePort(0))))
              .addClustersItem(new Cluster() //cluster
                  .clusterName(cluster1Name)
                  .replicas(replicaCount)
                  .serverStartState("RUNNING")));
      setPodAntiAffinity(domain);

      // verify the domain custom resource is created
      createDomainAndVerify(domain, domainNamespace);

      //check the desired completed, available and failed statuses
      checkStatus("True", "False", "True");

    } finally {
      deleteDomainResource(domainNamespace, domainUid);
    }
  }

  /**
   * Test domain status failed condition with non-existent admin secret. Verify the following conditions are
   * generated: type: Completed, status: true type: Available, status: false type: Failed status: true
   */
  @Test
  @DisplayName("Test domain status condition with non-existent admin secret")
  void testNonexistentAdminSecret() {
    String image = TestConstants.MII_BASIC_IMAGE_NAME + ":" + TestConstants.MII_BASIC_IMAGE_TAG;

    logger.info("Creating domain custom resource");
    Domain domain = createDomainResource(domainUid, domainNamespace, "non-existent-secret",
        OCIR_SECRET_NAME, encryptionSecretName, replicaCount, image);

    try {
      logger.info("Creating domain");
      createDomainAndVerify(domain, domainNamespace);

      //check the desired completed, available and failed statuses
      checkStatus("True", "False", "True");

    } finally {
      deleteDomainResource(domainNamespace, domainUid);
    }
  }


  /**
   * Test domain status failed condition with invalid node port. Verify the following conditions are
   * generated: type: Completed, status: true type: Available, status: false type: Failed status: true
   */
  @Test
  @DisplayName("Test domain status failed condition with invalid node port.")
  void testInvalidNodePort() {
    String image = MII_BASIC_IMAGE_NAME + ":" + MII_BASIC_IMAGE_TAG;

    logger.info("Creating domain custom resource");
    Domain domain = createDomainResource(domainUid, domainNamespace, adminSecretName,
        OCIR_SECRET_NAME, encryptionSecretName, replicaCount, image);

    AdminServer as = new AdminServer()
        .serverStartState("RUNNING")
        .adminService(new AdminService()
            .addChannelsItem(new Channel()
                .channelName("default")
                .nodePort(19000)));
    domain.getSpec().adminServer(as);

    try {
      logger.info("Creating domain");
      createDomainAndVerify(domain, domainNamespace);

      //check the desired completed, available and failed statuses
      checkStatus("True", "False", "True");

    } finally {
      deleteDomainResource(domainNamespace, domainUid);
    }
  }

  /**
   * Test domain status failed condition with introspector failure. Verify the following conditions are
   * generated: type: Completed, status: true type: Available, status: false type: Failed status: true
   */
  @Test
  @DisplayName("Test domain status condition with introspector failure")
  void testIntrospectorFailure() {
    String image = MII_BASIC_IMAGE_NAME + ":" + MII_BASIC_IMAGE_TAG;

    logger.info("Creating domain custom resource");
    Domain domain = createDomainResource(domainUid, domainNamespace, adminSecretName,
        OCIR_SECRET_NAME, encryptionSecretName, replicaCount, image);

    domain.getSpec().configuration().introspectorJobActiveDeadlineSeconds(5L);

    try {
      logger.info("Creating domain");
      createDomainAndVerify(domain, domainNamespace);

      //check the desired completed, available and failed statuses
      checkStatus("True", "False", "True");

    } finally {
      deleteDomainResource(domainNamespace, domainUid);
    }
  }


  /**
   * Test domain status condition with serverStartPolicy set to IF_NEEDED. Verify the following conditions are
   * generated: type: Completed, status: true type: Available, status: true Verify no Failed type condition generated.
   */
  @Test
  @DisplayName("Test domain status condition with serverStartPolicy set to IF_NEEDED")
  void testMSBootFailureStatus() {
    try {

      String fmwMiiImage = null;

      String rcuSchemaPrefix = "FMWDOMAINMII";
      String oracleDbUrlPrefix = "oracledb.";
      String oracleDbSuffix = null;
      String rcuSysUserName = "sys";
      String rcuSysPassword = "Oradoc_db1";
      String rcuSchemaUserName = "myrcuuser";
      String rcuSchemaPassword = "Oradoc_db1";
      String rcuSchemaPasswordNew = "Oradoc_db2";
      String modelFile = "model-singleclusterdomain-sampleapp-jrf.yaml";

      String rcuaccessSecretName = domainUid + "-rcu-access";
      String opsswalletpassSecretName = domainUid + "-opss-wallet-password-secret";
      String opsswalletfileSecretName = domainUid + "opss-wallet-file-secret";

      String dbUrl = oracleDbUrlPrefix + domainNamespace + oracleDbSuffix;

      // create RCU access secret
      logger.info("Creating RCU access secret: {0}, with prefix: {1}, dbUrl: {2}, schemapassword: {3})",
          rcuaccessSecretName, rcuSchemaPrefix, rcuSchemaPassword, dbUrl);
      assertDoesNotThrow(() -> createRcuAccessSecret(rcuaccessSecretName,
          domainNamespace,
          rcuSchemaPrefix,
          rcuSchemaPassword,
          dbUrl),
          String.format("createSecret failed for %s", rcuaccessSecretName));

      logger.info("Create OPSS wallet password secret");
      assertDoesNotThrow(() -> createOpsswalletpasswordSecret(
          opsswalletpassSecretName,
          domainNamespace,
          "welcome1"),
          String.format("createSecret failed for %s", opsswalletpassSecretName));

      logger.info("Create an image with jrf model file");
      final List<String> modelList = Collections.singletonList(MODEL_DIR + "/" + modelFile);
      fmwMiiImage = createMiiImageAndVerify(
          "jrf-mii-image-status",
          modelList,
          Collections.singletonList(MII_BASIC_APP_NAME),
          FMWINFRA_IMAGE_NAME,
          FMWINFRA_IMAGE_TAG,
          "JRF",
          false);

      // push the image to a registry to make it accessible in multi-node cluster
      dockerLoginAndPushImageToRegistry(fmwMiiImage);

      // create the domain object
      Domain domain = FmwUtils.createDomainResource(domainUid,
          domainNamespace,
          adminSecretName,
          OCIR_SECRET_NAME,
          encryptionSecretName,
          rcuaccessSecretName,
          opsswalletpassSecretName,
          replicaCount,
          fmwMiiImage);

      createDomainAndVerify(domain, domainNamespace);

      //check the desired completed, available and failed statuses
      checkStatus("True", "False", "True");

    } finally {
      deleteDomainResource(domainNamespace, domainUid);
    }
  }

  /**
   * Cleanup the persistent volume and persistent volume claim used by the test.
   */
  @AfterAll
  public static void tearDown() {
    if (System.getenv("SKIP_CLEANUP") == null
        || (System.getenv("SKIP_CLEANUP") != null
        && System.getenv("SKIP_CLEANUP").equalsIgnoreCase("false"))) {
      deletePersistentVolumeClaim(pvcName, domainNamespace);
      deletePersistentVolume(pvName);
    }
  }

  // Create a domain resource with a custom ConfigMap
  private Domain createDomainResourceWithConfigMap(String domainUid,
          String domNamespace, String adminSecretName,
          String repoSecretName, String encryptionSecretName,
          int replicaCount, String miiImage, String configmapName, Long introspectorDeadline) {

    Map keyValueMap = new HashMap<String, String>();
    keyValueMap.put("testkey", "testvalue");

    // create the domain CR
    Domain domain = new Domain()
        .apiVersion(DOMAIN_API_VERSION)
        .kind("Domain")
        .metadata(new V1ObjectMeta()
            .name(domainUid)
            .namespace(domNamespace))
        .spec(new DomainSpec()
            .domainUid(domainUid)
            .domainHomeSourceType("FromModel")
            .image(miiImage)
            .addImagePullSecretsItem(new V1LocalObjectReference()
                .name(repoSecretName))
            .webLogicCredentialsSecret(new V1SecretReference()
                .name(adminSecretName)
                .namespace(domNamespace))
            .includeServerOutInPodLog(true)
            .serverStartPolicy("IF_NEEDED")
            .serverPod(new ServerPod()
                .addEnvItem(new V1EnvVar()
                    .name("JAVA_OPTIONS")
                    .value("-Dweblogic.security.SSL.ignoreHostnameVerification=true"))
                .addEnvItem(new V1EnvVar()
                    .name("USER_MEM_ARGS")
                    .value("-Djava.security.egd=file:/dev/./urandom ")))
            .adminServer(new AdminServer()
                .serverStartState("RUNNING")
                .serverService(new ServerService()
                    .annotations(keyValueMap)
                    .labels(keyValueMap))
                .adminService(new AdminService()
                    .addChannelsItem(new Channel()
                        .channelName("default")
                        .nodePort(0))))
            .addClustersItem(new Cluster()
                .clusterName("cluster-1")
                .replicas(replicaCount)
                .serverStartState("RUNNING"))
            .configuration(new Configuration()
                .model(new Model()
                    .domainType("WLS")
                    .configMap(configmapName)
                    .runtimeEncryptionSecret(encryptionSecretName))
                .introspectorJobActiveDeadlineSeconds(introspectorDeadline != null ? introspectorDeadline : 300L)));
    setPodAntiAffinity(domain);
    return domain;
  }

  private Domain createDomainResource(String domainUid, String domNamespace, String adminSecretName,
      String repoSecretName, String encryptionSecretName, int replicaCount,
      String miiImage) {
    // create the domain CR
    Domain domain = new Domain()
        .apiVersion(DOMAIN_API_VERSION)
        .kind("Domain")
        .metadata(new V1ObjectMeta()
            .name(domainUid)
            .namespace(domNamespace))
        .spec(new DomainSpec()
            .domainUid(domainUid)
            .domainHomeSourceType("FromModel")
            .image(miiImage)
            .addImagePullSecretsItem(new V1LocalObjectReference()
                .name(repoSecretName))
            .webLogicCredentialsSecret(new V1SecretReference()
                .name(adminSecretName)
                .namespace(domNamespace))
            .includeServerOutInPodLog(true)
            .serverStartPolicy("IF_NEEDED")
            .serverPod(new ServerPod()
                .addEnvItem(new V1EnvVar()
                    .name("JAVA_OPTIONS")
                    .value("-Dweblogic.security.SSL.ignoreHostnameVerification=true"))
                .addEnvItem(new V1EnvVar()
                    .name("USER_MEM_ARGS")
                    .value("-Djava.security.egd=file:/dev/./urandom ")))
            .adminServer(new AdminServer()
                .serverStartState("RUNNING")
                .adminService(new AdminService()
                    .addChannelsItem(new Channel()
                        .channelName("default")
                        .nodePort(0))))
            .addClustersItem(new Cluster()
                .clusterName("cluster-1")
                .replicas(replicaCount)
                .serverStartState("RUNNING"))
            .configuration(new Configuration()
                .model(new Model()
                    .domainType("WLS")
                    .runtimeEncryptionSecret(encryptionSecretName))
                .introspectorJobActiveDeadlineSeconds(300L)));
    setPodAntiAffinity(domain);
    return domain;
  }

  // check the desired statuses of Completed, Available and Failed type conditions
  private void checkStatus(String completed, String available, String failed) {

    if (completed != null) {
      // verify the condition type Failed exists
      checkDomainStatusConditionTypeExists(domainUid, domainNamespace, DOMAIN_STATUS_CONDITION_COMPLETED_TYPE);
      // verify the condition Failed type has status True
      checkDomainStatusConditionTypeHasExpectedStatus(domainUid, domainNamespace,
          DOMAIN_STATUS_CONDITION_COMPLETED_TYPE, completed);
    }

    if (available != null) {
      // verify the condition type Completed exists
      checkDomainStatusConditionTypeExists(domainUid, domainNamespace, DOMAIN_STATUS_CONDITION_AVAILABLE_TYPE);
      // verify the condition Completed type has status True
      checkDomainStatusConditionTypeHasExpectedStatus(domainUid, domainNamespace,
          DOMAIN_STATUS_CONDITION_AVAILABLE_TYPE, available);
    }
    if (failed != null) {
      // verify the condition type Available exists
      checkDomainStatusConditionTypeExists(domainUid, domainNamespace, DOMAIN_STATUS_CONDITION_FAILED_TYPE);
      // verify the condition Available type has status False
      checkDomainStatusConditionTypeHasExpectedStatus(domainUid, domainNamespace,
          DOMAIN_STATUS_CONDITION_FAILED_TYPE, failed);
    }
  }

}
