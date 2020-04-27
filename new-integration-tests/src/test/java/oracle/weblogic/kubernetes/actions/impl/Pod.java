// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes.actions.impl;

import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import oracle.weblogic.kubernetes.actions.impl.primitive.Kubernetes;

public class Pod {

  /**
   * Delete Kubernetes Pod.
   *
   * @param name name of the pod
   * @param namespace name of namespace
   * @return true if successful, false otherwise
  */
  public static boolean deletePod(String name, String namespace) {
    return Kubernetes.deletePod(name, namespace);
  }

  /**
   * List Kubernetes pods in a namesapce.
   *
   * @param namespace name of namespace
   * @param labelSelectors with which pods are decorated
   * @return V1PodList list of pods
  */
  public static V1PodList listPods(String namespace, String labelSelectors) {
    return Kubernetes.listPods(namespace,labelSelectors);
  }

  /**
   * Get a pod's log.
   *  
   * @param podName name of the pod
   * @param namespace name of the Namespace
   * @return log as a String
  */
  public static String getPodLog(String podName, String namespace) {
    return Kubernetes.getPodLog(podName,namespace);
  }

  /**
   * Get the creationTimestamp for a given pod with following parameters.
   * @param namespace in which to check for the pod existence
   * @param labelSelector in the format "weblogic.domainUID in (%s)"
   * @param podName name of the pod 
   * @return creationTimestamp from metadata section of the pod
   */
  public static String getPodCreationTimestamp(String namespace, String labelSelector, String podName) {
    return Kubernetes.getPodCreationTimestamp(namespace,labelSelector,podName);
  }

  /**
   * Get the Kubernetes pod object with following parameters.
   * @param namespace in which to check for the pod existence
   * @param labelSelector in the format "weblogic.domainUID in (%s)"
   * @param podName name of the pod 
   * @return V1Pod pod object
   */
  public static V1Pod getPod(String namespace, String labelSelector, String podName) {
    return Kubernetes.getPod(namespace, labelSelector, podName);
  }
}
