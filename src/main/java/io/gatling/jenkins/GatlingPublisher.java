/**
 * Copyright 2011-2020 GatlingCorp (http://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.jenkins;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.init.Initializer;
import hudson.init.InitMilestone;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import jenkins.tasks.SimpleBuildStep;
import org.kohsuke.stapler.DataBoundConstructor;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GatlingPublisher extends Recorder implements SimpleBuildStep {

  private final Boolean enabled;

  @DataBoundConstructor
  public GatlingPublisher(Boolean enabled) {
    this.enabled = enabled;
  }


  @Override
  public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
    FilePath workspace = build.getWorkspace();
    if (workspace == null) {
      listener.getLogger().println("Failed to access workspace, it may be on a non-connected slave.");
      return false;
    }

    perform(build, workspace, launcher, listener);
    return true;
  }

  @Override
  public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull TaskListener listener)
          throws InterruptedException, IOException {
    PrintStream logger = listener.getLogger();
    if (enabled == null) {
      logger.println("Cannot check Gatling simulation tracking status, reports won't be archived.");
      logger.println("Please make sure simulation tracking is enabled in your build configuration !");
      return;
    }
    if (!enabled) {
      logger.println("Simulation tracking disabled, reports were not archived.");
      return;
    }

    logger.println("Archiving Gatling reports...");

    List<BuildSimulation> sims = saveFullReports(run, workspace, run.getRootDir(), logger);
    if (sims.isEmpty()) {
      logger.println("No newer Gatling reports to archive.");
      return;
    }

    addOrUpdateBuildAction(run, sims);
  }

  private void addOrUpdateBuildAction(@Nonnull Run<?, ?> run, List<BuildSimulation> simulations) {
    GatlingBuildAction action = run.getAction(GatlingBuildAction.class);

    if (action != null) {
      action.getSimulations().addAll(simulations);
    } else {
      action = new GatlingBuildAction(run, simulations);
      run.addAction(action);
    }
  }

  @SuppressWarnings("unused")
  public boolean isEnabled() {
    return enabled != null && enabled;
  }

  public BuildStepMonitor getRequiredMonitorService() {
    return BuildStepMonitor.BUILD;
  }

  @SuppressFBWarnings(value="NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
  private List<BuildSimulation> saveFullReports(@Nonnull Run<?,?> run, @Nonnull FilePath workspace, @Nonnull File rootDir, @Nonnull PrintStream logger)
          throws IOException, InterruptedException {
    FilePath[] files = workspace.list("**/global_stats.json");
    List<FilePath> reportFolders = new ArrayList<>();

    if (files.length == 0) {
      logger.println("Could not find a Gatling report in results folder.");
      return Collections.emptyList();
    }

    // Get reports folders for all "global_stats.json" found
    for (FilePath file : files) {
      reportFolders.add(file.getParent().getParent());
    }

    List<FilePath> reportsToArchive = selectReports(run, reportFolders, logger);


    // If the most recent report has already been archived, there's nothing else to do
    if (reportsToArchive.isEmpty()) {
      return Collections.emptyList();
    }

    List<BuildSimulation> simsToArchive = new ArrayList<>();

    File allSimulationsDirectory = new File(rootDir, "simulations");
    if (!allSimulationsDirectory.exists()&& !allSimulationsDirectory.mkdir()) {
      logger.println("Could not create simulations archive directory '" + allSimulationsDirectory + "'");
      return Collections.emptyList();
    }

    for (FilePath reportToArchive : reportsToArchive) {
      String name = reportToArchive.getName();
      int dashIndex = name.lastIndexOf('-');
      String simulation = name.substring(0, dashIndex);
      File simulationDirectory = new File(allSimulationsDirectory, name);

      if (simulationDirectory.exists()) {
        logger.printf("Simulation archive directory '%s' already exists, skipping.%n", simulationDirectory);
        continue;
      }

      if (!simulationDirectory.mkdir()) {
        logger.printf("Could not create simulation archive directory '%s', skipping.%n", simulationDirectory);
        continue;
      }

      FilePath reportDirectory = new FilePath(simulationDirectory);

      reportToArchive.copyRecursiveTo(reportDirectory);

      SimulationReport report = new SimulationReport(reportDirectory, simulation);
      report.readStatsFile();
      BuildSimulation sim = new BuildSimulation(simulation, report.getGlobalReport(), simulationDirectory);

      simsToArchive.add(sim);
    }


    return simsToArchive;
  }

  @Nonnull
  private static List<FilePath> selectReports(@Nonnull Run<?, ?> run, @Nonnull List<FilePath> reportFolders,
                                              @Nonnull PrintStream logger) throws InterruptedException, IOException {
    long buildStartTime = run.getStartTimeInMillis();
    List<FilePath> reportsFromThisBuild = new ArrayList<>();
    for (FilePath reportFolder : reportFolders) {
      long reportLastMod = reportFolder.lastModified();

      if (reportLastMod > buildStartTime) {
        logger.println("Adding report '" + reportFolder.getName() + "'");
        reportsFromThisBuild.add(reportFolder);
      }
    }
    return reportsFromThisBuild;
  }

  @Extension
  public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

    @Override
    public boolean isApplicable(Class<? extends AbstractProject> aClass) {
      return true;
    }

    @Override
    @Nonnull
    public String getDisplayName() {
      return Messages.title();
    }

    @Initializer(before = InitMilestone.PLUGINS_STARTED)
    @SuppressWarnings("unused")
    public static void addAliases() {
      Items.XSTREAM2.addCompatibilityAlias("io.gatling.jenkins.GatlingPublisher", GatlingPublisher.class);
      Items.XSTREAM2.addCompatibilityAlias("io.gatling.jenkins.GatlingBuildAction", GatlingBuildAction.class);
    }
  }
}
