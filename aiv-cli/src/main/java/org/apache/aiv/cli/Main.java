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

package org.apache.aiv.cli;

import org.apache.aiv.adapter.git.GitDiffProvider;
import org.apache.aiv.adapter.github.StdoutReportPublisher;
import org.apache.aiv.cli.config.YamlConfigProvider;
import org.apache.aiv.core.Orchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CLI entry point. Usage: aiv run --workspace /path --diff origin/main
 *
 * @author Vaquar Khan
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Path workspace = Paths.get(".").toAbsolutePath();
        String baseRef = "origin/main";
        String headRef = "HEAD";

        for (int i = 0; i < args.length; i++) {
            if ("--workspace".equals(args[i]) && i + 1 < args.length) {
                workspace = Paths.get(args[++i]).toAbsolutePath();
            } else if ("--diff".equals(args[i]) && i + 1 < args.length) {
                baseRef = args[++i];
            } else if ("--head".equals(args[i]) && i + 1 < args.length) {
                headRef = args[++i];
            }
        }

        try {
            var diffProvider = new GitDiffProvider();
            var configProvider = new YamlConfigProvider();
            var reportPublisher = new StdoutReportPublisher();
            var orchestrator = new Orchestrator(diffProvider, configProvider, reportPublisher);
            return orchestrator.run(workspace, baseRef, headRef);
        } catch (IllegalArgumentException e) {
            log.error("AIV error: {}", e.getMessage());
            return 1;
        }
    }
}
