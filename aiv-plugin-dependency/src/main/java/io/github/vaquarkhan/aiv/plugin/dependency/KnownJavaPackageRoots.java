/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vaquarkhan.aiv.plugin.dependency;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps Maven coordinates to typical Java package roots. Maven {@code groupId} often differs from
 * import prefixes; this table reduces false positives for the dependency gate.
 */
final class KnownJavaPackageRoots {

    private static final Map<String, List<String>> GROUP_TO_PREFIXES = new HashMap<>();
    private static final Map<String, List<String>> GROUP_ARTIFACT_TO_PREFIXES = new HashMap<>();

    static {
        putG("com.google.guava", "com.google.common", "com.google.thirdparty.publicsuffix");
        putG("commons-io", "org.apache.commons.io");
        putG("org.apache.commons", "org.apache.commons.lang3", "org.apache.commons.lang", "org.apache.commons.io",
                "org.apache.commons.collections4", "org.apache.commons.text");
        putGA("com.fasterxml.jackson.core", "jackson-databind", "com.fasterxml.jackson.databind");
        putGA("com.fasterxml.jackson.core", "jackson-core", "com.fasterxml.jackson.core");
        putGA("com.fasterxml.jackson.core", "jackson-annotations", "com.fasterxml.jackson.annotation");
        putG("org.jetbrains.kotlin", "kotlin", "kotlinx");
        putG("org.projectlombok", "lombok");
        putG("org.slf4j", "org.slf4j");
        putG("ch.qos.logback", "ch.qos.logback");
        putG("org.junit.jupiter", "org.junit.jupiter", "org.junit.jupiter.api");
        putG("org.junit.platform", "org.junit.platform");
        putG("org.mockito", "org.mockito");
        putG("org.assertj", "org.assertj.core");
        putG("com.google.code.findbugs", "edu.umd.cs.findbugs");
        putG("com.google.protobuf", "com.google.protobuf");
        putG("io.grpc", "io.grpc");
        putG("org.apache.httpcomponents", "org.apache.http");
        putG("org.eclipse.jetty", "org.eclipse.jetty");
        putG("org.springframework", "org.springframework");
        putG("io.micrometer", "io.micrometer");
        putG("jakarta.servlet", "jakarta.servlet");
        putG("jakarta.validation", "jakarta.validation");
        putG("javax.inject", "javax.inject");
        putG("com.google.code.gson", "com.google.gson");
        putG("org.apache.maven", "org.apache.maven");
        putG("org.codehaus.plexus", "org.codehaus.plexus");
        putG("org.ow2.asm", "org.objectweb.asm");
    }

    private static void putG(String groupId, String... prefixes) {
        GROUP_TO_PREFIXES.put(groupId, List.of(prefixes));
    }

    private static void putGA(String groupId, String artifactId, String... prefixes) {
        GROUP_ARTIFACT_TO_PREFIXES.put(groupId + ":" + artifactId, List.of(prefixes));
    }

    static Set<String> expandPrefixes(String groupId, String artifactId) {
        List<String> out = new ArrayList<>();
        if (artifactId != null && !artifactId.isBlank()) {
            List<String> ga = GROUP_ARTIFACT_TO_PREFIXES.get(groupId + ":" + artifactId.trim());
            if (ga != null) {
                out.addAll(ga);
            }
        }
        List<String> g = GROUP_TO_PREFIXES.get(groupId);
        if (g != null) {
            out.addAll(g);
        }
        if (out.isEmpty() && groupId != null && !groupId.isEmpty()) {
            out.add(groupId);
        }
        return Set.copyOf(out);
    }

    private KnownJavaPackageRoots() {
    }
}
