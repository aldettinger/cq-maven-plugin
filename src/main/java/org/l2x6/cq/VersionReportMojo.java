/**
 * Copyright (c) 2020 CQ Maven Plugin
 * project contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.l2x6.cq;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.l2x6.cq.CqCatalog.Kind;
import org.l2x6.cq.CqCatalog.WrappedModel;

/**
 * Prepares a report upon releasing a new Camel Quarkus version.
 *
 * @since 0.3.0
 */
@Mojo(name = "whatsnew", threadSafe = true, requiresProject = false)
public class VersionReportMojo extends AbstractExtensionListMojo {

    /**
     * Two Camel Quarkus versions to compare, delimited by {@code ..}, e.g. {@code -Dcq.versions=1.0.0.M6..1.0.0.M7}.
     *
     * @since 0.3.0
     */
    @Parameter(property = "cq.versions")
    String versions;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipArtifactIdBases == null) {
            skipArtifactIdBases = Collections.emptySet();
        }
        final String delim = "..";
        final int delimPos = versions.indexOf(delim);
        if (delimPos <= 0) {
            throw new IllegalStateException("Expected versions delimited by '..': found '"+ versions +"'");
        }
        final String baselineVersion = versions.substring(0, delimPos);
        final String reportVersion = versions.substring(delimPos + delim.length());
        final List<String> versions = Arrays.asList(baselineVersion, reportVersion);
        try {
            final Path tempDir = Files.createTempDirectory(getClass().getSimpleName());

            for (String version : versions) {
                final String uri = String.format(
                        "https://repository.apache.org/content/groups/public/org/apache/camel/quarkus/camel-quarkus-catalog/%s/camel-quarkus-catalog-%s.jar",
                        version, version);
                final URL url = new URL(uri);
                try (InputStream in = url.openStream();
                        OutputStream out = Files.newOutputStream(tempDir.resolve(version + ".jar"))) {
                    final byte[] buf = new byte[4096];
                    int len;
                    while ((len = in.read(buf)) >= 0) {
                        out.write(buf, 0, len);
                    }
                }
            }
            final StringBuilder counts = new StringBuilder();
            final StringBuilder details = new StringBuilder();

            try (FileSystem currentCatalogFs = FileSystems.newFileSystem(tempDir.resolve(reportVersion + ".jar"),
                    (ClassLoader) null);
                    FileSystem previousCatalogFs = FileSystems.newFileSystem(tempDir.resolve(baselineVersion + ".jar"),
                            (ClassLoader) null)) {
                final CqCatalog currentCatalog = new CqCatalog(currentCatalogFs.getRootDirectories().iterator().next());
                final CqCatalog previousCatalog = new CqCatalog(previousCatalogFs.getRootDirectories().iterator().next());

                for (Kind kind : Kind.values()) {
                    final String pluralName = CreateExtensionMojo.toCapCamelCase(kind.getPluralName());
                    final AtomicInteger cnt = new AtomicInteger();
                    final String kindItem = pluralName + ":\n";
                    details.append(kindItem);
                    kind.all(currentCatalog)
                            .sorted()
                            .forEach(currentModel -> {
                                if (reportVersion.equals(currentModel.getFirstVersion())) {
                                    /* added in this version */
                                    details.append("• ").append(currentModel.getTitle());
                                    if ("JVM".equals(currentModel.getTarget())) {
                                        details.append(" (JVM only)");
                                    }
                                    details.append('\n');
                                    cnt.incrementAndGet();
                                } else {
                                    /* added earlier */
                                    if (!"JVM".equals(currentModel.getTarget())) {
                                        /* It is native now, check whether was JVM in the previous version */
                                        try {
                                            Optional<WrappedModel> previousModel = kind.load(previousCatalog,
                                                    currentModel.getScheme());
                                            if (previousModel.isPresent() && "JVM".equals(previousModel.get().getTarget())) {
                                                details.append("• ").append(currentModel.getTitle()).append(" +native")
                                                        .append('\n');
                                            }
                                        } catch (RuntimeException e) {
                                            if (e.getCause().getClass() == NoSuchFileException.class) {

                                            }
                                        }
                                    }
                                }
                            });
                    if (cnt.get() == 0) {
                        details.delete(details.length() - kindItem.length(), details.length());
                    } else {
                        counts.append("• ").append(cnt.get()).append(" new ").append(kind.getPluralName()).append("\n");
                    }
                }
            }

            getLog().info("Counts:\n\n\n" + counts.toString() + "\n\n");
            getLog().info("Report:\n\n\n" + details.toString() + "\n\n");

        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }



    }

}