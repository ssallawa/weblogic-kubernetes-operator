// Copyright (c) 2019, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

import oracle.kubernetes.operator.utils.DbUtils;
import oracle.kubernetes.operator.utils.DomainCrd;
import oracle.kubernetes.operator.utils.ExecResult;
import oracle.kubernetes.operator.utils.JrfDomain;
import oracle.kubernetes.operator.utils.LoggerHelper;
import oracle.kubernetes.operator.utils.Operator;
import oracle.kubernetes.operator.utils.TestUtils;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)

public class ItJrfPvWlst extends BaseTest {
  //property file used to customize operator properties for operator inputs yaml
  private static final String JRF_OPERATOR_FILE_1 = "jrfoperator1.yaml";
  // file used to customize domain properties for domain, PV and LB inputs yaml
  private static final String JRF_DOMAIN_ON_PV_WLST_FILE = "jrf_domainonpvwlst.yaml";
  private static String rcuSchemaPrefix = "jrfdomain";
  private static Operator operator1;
  private static String domainNS;
  private static String domainUid = "";
  private static String restartTmpDir = "";
  private static boolean testCompletedSuccessfully;
  private static String testClassName;

  /**
  * This method gets called only once before any of the test methods are executed. It does the
  * initialization of the integration test properties defined in OperatorIT.properties and setting
  * the resultRoot, pvRoot and projectRoot attributes. It also creates Oracle DB pod which used for
  * RCU.
  *
  * @throws Exception - if an error occurs when load property file or create DB pod
  */
  @BeforeClass
  public static void staticPrepare() throws Exception {
    testClassName = new Object() {
    }.getClass().getEnclosingClass().getSimpleName();
    // initialize test properties and create the directories
    initialize(APP_PROPS_FILE, testClassName);
    setMaxIterationsPod(80);
    TestUtils.exec(
        "cp -rf " 
        + BaseTest.getProjectRoot() 
        + "/kubernetes/samples/scripts/create-rcu-schema " 
        + BaseTest.getResultDir(),
        true);
   
    DbUtils.startOracleDB();
    DbUtils.createRcuSchema(rcuSchemaPrefix);

   
  }

  /**
  * This method will run once after all test methods are finished. It Releases k8s cluster lease,
  * archives result, pv directories.
  *
  * @throws Exception - if any error occurs
  */
  @AfterClass
  public static void staticUnPrepare() throws Exception {
    DbUtils.deleteRcuPod();
    DbUtils.stopOracleDB();
    //tearDown(new Object() {}.getClass().getEnclosingClass().getSimpleName());

    LoggerHelper.getLocal().log(Level.INFO,"SUCCESS");
  }
 
  @Test
  public void testJrfDomainOnPvUsingWlst() throws Exception {
    if (QUICKTEST) {
      String testMethodName = new Object() {
      }.getClass().getEnclosingMethod().getName();
      logTestBegin(testMethodName);
      LoggerHelper.getLocal().log(Level.INFO,
          "Creating Operator & waiting for the script to complete execution");
      // create operator1
      if (operator1 == null) {
        Map<String, Object> operatorMap = TestUtils.createOperatorMap(getNewSuffixCount(), true, testClassName);
        operator1 = TestUtils.createOperator(operatorMap, Operator.RestCertType.SELF_SIGNED);
        Assert.assertNotNull(operator1);
        domainNS = ((ArrayList<String>) operatorMap.get("domainNamespaces")).get(0);;
      }

      JrfDomain jrfdomain = null;
      boolean testCompletedSuccessfully = false;

      try {
        // create JRF domain
        Map<String, Object> domainMap = TestUtils.createDomainMap(getNewSuffixCount(), testClassName);
        domainMap.put("namespace", domainNS);
        domainMap.put("initialManagedServerReplicas", new Integer("2"));
        domainMap.put("image", "container-registry.oracle.com/middleware/fmw-infrastructure:12.2.1.3");
        domainMap.put("clusterName", "infra-cluster");
        domainMap.put("managedServerNameBase", "infraserver");
        domainMap.put("rcuSchemaPrefix", "jrfdomain");
        domainMap.put("rcuDatabaseURL", "oracle-db.default.svc.cluster.local:1521/devpdb.k8s");
        domainUid = (String) domainMap.get("domainUID");
        LoggerHelper.getLocal().log(Level.INFO,
            "Creating and verifying the domain creation with domainUid: " + domainUid);

        jrfdomain = new JrfDomain(domainMap);
        jrfdomain.verifyDomainCreated();
        
        // basic test cases
        testBasicUseCases(jrfdomain, false);

        testCompletedSuccessfully = true;
      } finally {
        if (jrfdomain != null  && (JENKINS || testCompletedSuccessfully)) {
          jrfdomain.shutdownUsingServerStartPolicy();
        }
      }

      LoggerHelper.getLocal().log(Level.INFO, "SUCCESS - " + testMethodName);
    }
  }
}
