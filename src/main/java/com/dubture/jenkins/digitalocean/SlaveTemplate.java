/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 robert.gruendler@dubture.com
 *               2016 Maxim Biro <nurupo.contributions@gmail.com>
 *               2017 Harald Sitter <sitter@kde.org>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.dubture.jenkins.digitalocean;

import java.io.IOException;
import java.util.Arrays;
import java.time.LocalDateTime;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.base.Strings;
import com.myjeeva.digitalocean.exception.DigitalOceanException;
import com.myjeeva.digitalocean.exception.RequestUnsuccessfulException;
import com.myjeeva.digitalocean.impl.DigitalOceanClient;
import com.myjeeva.digitalocean.pojo.Droplet;
import com.myjeeva.digitalocean.pojo.Image;
import com.myjeeva.digitalocean.pojo.Key;
import com.myjeeva.digitalocean.pojo.Region;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import hudson.Extension;
import hudson.RelativePath;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.XStream2;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Lists.newLinkedList;
import static java.lang.String.format;

/**
 * A {@link SlaveTemplate} represents the configuration values for creating a new slave via a DigitalOcean droplet.
 *
 * <p>Holds things like Image ID, sizeId and region used for the specific droplet.
 *
 * <p>The {@link SlaveTemplate#provision} method
 * is the main entry point to create a new droplet via the DigitalOcean API when a new slave needs to be provisioned.
 *
 * @author robert.gruendler@dubture.com
 */
@SuppressWarnings("unused")
public class SlaveTemplate implements Describable<SlaveTemplate> {

    private final String name;

    private final String labelString;

    private final int idleTerminationInMinutes;

    /**
     * The maximum number of executors that this slave will run.
     */
    private final int numExecutors;

    private final String labels;

    private final Boolean labellessJobsAllowed;

    /**
     * The Image to be used for the droplet.
     */
    private final String imageId;

    /**
     * The region for the droplet.
     */
    private final String regionId;

    private final String username;

    private final String workspacePath;

    private final Integer sshPort;

    private final Integer instanceCap;

    private final Boolean installMonitoringAgent;

    private final String tags;

    /**
     * User-supplied data for configuring a droplet
     */
    private final String userData;

    /**
     * Setup script for preparing the new slave. Differs from userData in that Jenkins runs this script,
     * as opposed to the DigitalOcean provisioning process.
     */
    private final String initScript;

    private LocalDateTime errorTime;

    private transient Set<LabelAtom> labelSet;

    private static final Logger LOGGER = Logger.getLogger(SlaveTemplate.class.getName());

    // Compatibility with old configs. We'll unmarshal the sizeId but then convert it to a dropletConfig in our
    // ConverterImpl. Saves us from unmarshalling the entire object whilst still being fully backwards compatible.
    private String sizeId = "";

    private DropletConfig dropletConfig;
    public DropletConfig getDropletConfig() { return dropletConfig; }

    private final List<? extends DropletConfig> fallbackConfigs;
    public List<DropletConfig> getFallbackConfigs() { return Collections.unmodifiableList(fallbackConfigs); }

    /**
     * Data is injected from the global Jenkins configuration via jelly.
     * @param name image name
     * @param imageId an image slug e.g. "debian-8-x64", or an integer e.g. of a backup, such as "12345678"
     * @param sizeId the image size e.g. "512mb" or "1gb"
     * @param regionId the region e.g. "nyc1"
     * @param username username to login
     * @param workspacePath path to the workspace
     * @param sshPort ssh port to be used
     * @param idleTerminationInMinutes how long to wait before destroying a slave
     * @param numExecutors the number of executors that this slave supports
     * @param labelString the label for this slave
     * @param labellessJobsAllowed if jobs without a label are allowed
     * @param instanceCap if the number of created instances is capped
     * @param installMonitoring whether expanded monitoring tool agent should be installed
     * @param tags the droplet tags
     * @param userData user data for DigitalOcean to apply when building the slave
     * @param initScript setup script to configure the slave
     */
    @DataBoundConstructor
    public SlaveTemplate(String name, String imageId, DropletConfig dropletConfig, String regionId, String username, String workspacePath,
                         Integer sshPort, String idleTerminationInMinutes, String numExecutors, String labelString,
                         Boolean labellessJobsAllowed, String instanceCap, Boolean installMonitoring, String tags,
                         String userData, String initScript,
                         List<? extends DropletConfig> fallbackConfigs) {

        LOGGER.log(Level.INFO, "Creating SlaveTemplate with imageId = {0}, sizeId = {1}, regionId = {2}",
                new Object[] { imageId, dropletConfig.getSizeId(), regionId});

        this.name = name;
        this.imageId = imageId;

        this.dropletConfig = dropletConfig;
        if (fallbackConfigs == null) {
            this.fallbackConfigs = Collections.emptyList();
        } else {
            this.fallbackConfigs = fallbackConfigs;
        }

        this.regionId = regionId;
        this.username = username;
        this.workspacePath = workspacePath;
        this.sshPort = sshPort;

        this.idleTerminationInMinutes = tryParseInteger(idleTerminationInMinutes, 10);
        this.numExecutors = tryParseInteger(numExecutors, 1);
        this.labelString = labelString;
        this.labellessJobsAllowed = labellessJobsAllowed;
        this.labels = Util.fixNull(labelString);
        this.instanceCap = Integer.parseInt(instanceCap);
        this.installMonitoringAgent = installMonitoring;
        this.tags = tags;

        this.userData = userData;
        this.initScript = initScript;

        this.errorTime = null;

        readResolve();
    }


    public static final class ConverterImpl extends XStream2.PassthruConverter<SlaveTemplate> {

        public ConverterImpl(XStream2 xstream) {
            super(xstream);
        }

        @Override
        public boolean canConvert(Class type) {
            return type == SlaveTemplate.class;
        }

        @Override
        protected void callback(SlaveTemplate obj, UnmarshallingContext context) {
            if (null != obj.sizeId && !obj.sizeId.isEmpty()) {
                // Convert legacy sizeId to dropletConfig if present.
                obj.dropletConfig = new DropletConfig(obj.sizeId);
            }
        }

    }

    public boolean isInstanceCapReachedLocal(String cloudName) {
        if (instanceCap == 0) {
            return false;
        }

        int count = 0;
        List<Node> nodes = Jenkins.getInstance().getNodes();
        for (Node n : nodes) {
            if (DropletName.isDropletInstanceOfSlave(n.getDisplayName(), cloudName, name)) {
                count++;
            }
        }

        LOGGER.log(Level.INFO, "slave limit local check " + count + "/" + instanceCap + " " + (count >= instanceCap));

        return count >= instanceCap;
    }

    public boolean isInstanceCapReachedRemote(List<Droplet> droplets, String cloudName) throws DigitalOceanException {
        int count = 0;
        for (Droplet droplet : droplets) {
            if ((droplet.isActive() || droplet.isNew())) {
                if (DropletName.isDropletInstanceOfSlave(droplet.getName(), cloudName, name)) {
                    count++;
                }
            }
        }

        LOGGER.log(Level.INFO, "slave limit remote check " + count + "/" + instanceCap + " " + (count >= instanceCap));

        return count >= instanceCap;
    }

    public Slave provision(ProvisioningActivity.Id provisioningId,
                           String dropletName,
                           String cloudName,
                           String authToken,
                           String privateKey,
                           Integer sshKeyId,
                           List<Droplet> droplets,
                           Boolean usePrivateNetworking)
            throws IOException, RequestUnsuccessfulException, Descriptor.FormException {

        LOGGER.log(Level.INFO, "Provisioning slave... " + dropletName);

        try {
            LOGGER.log(Level.INFO, "Starting to provision digital ocean droplet using image: " + imageId + " region: " + regionId + ", sizeId: " + dropletConfig.getSizeId());

            if (isInstanceCapReachedLocal(cloudName) || isInstanceCapReachedRemote(droplets, cloudName)) {
                String msg = format("instance cap reached for %s in %s", dropletName, cloudName);
                LOGGER.log(Level.INFO, msg);
                throw new AssertionError(msg);
            }

            List<DropletConfig> configs = new ArrayList<DropletConfig>();
            configs.add(dropletConfig);
            for (DropletConfig c : getFallbackConfigs()) {
                configs.add(c);
            }
            LOGGER.log(Level.INFO, "built list");
//            configs.addAll(getFallbackConfigs());
            Iterator<DropletConfig> iterator = configs.iterator();
            DropletConfig config = null;
            LOGGER.log(Level.INFO, "going for a loopdy loop");
            while(true) {
                try {
                    // Find a config which isn't in error state
                    while (iterator.hasNext()) {
                        LOGGER.log(Level.INFO, "  next!");
                        config = iterator.next();
                        if (!config.isErroring()) {
                            break;
                            // Break loop once we have a config which isn't in error state.
                        }
                    }
                    // If we found no !erroring config we'll have the last config here. This is the final
                    // fallback and we'll simply assume it works no matter what.

                    LOGGER.log(Level.INFO, "done finding a not broken thing " + config.getSizeId());

                    // create a new droplet
                    Droplet droplet = new Droplet();
                    droplet.setName(dropletName);
                    droplet.setSize(config.getSizeId());
                    droplet.setRegion(new Region(regionId));
                    droplet.setImage(DigitalOcean.newImage(authToken, imageId));
                    droplet.setKeys(newArrayList(new Key(sshKeyId)));
                    droplet.setEnablePrivateNetworking(usePrivateNetworking);
                    droplet.setInstallMonitoring(installMonitoringAgent);
            droplet.setTags(Arrays.asList(Util.tokenize(Util.fixNull(tags))));

                    if (!(userData == null || userData.trim().isEmpty())) {
                        droplet.setUserData(userData);
                    }

                    LOGGER.log(Level.INFO, "Creating slave with new droplet " + dropletName + " " + config.getSizeId());

                    DigitalOceanClient apiClient = new DigitalOceanClient(authToken);
                    Droplet createdDroplet = apiClient.createDroplet(droplet);

                    return newSlave(provisioningId, cloudName, createdDroplet, privateKey);
        } catch (RuntimeException e) {
            throw e;
                } catch (Exception e) {
                    // Unexpected error, continuing iteration unless we are at the end.
                    config.setHadError();
                    String msg = format("Unexpected error raised during provisioning of %s:\n%s", dropletName, e.getMessage());
                    LOGGER.log(Level.WARNING,  msg, "got an error on config: " + config.getSizeId());
                    LOGGER.log(Level.WARNING,  msg, e);
                    if (!iterator.hasNext()) {
                        throw e;
                    }
                }
            }
        } catch (Exception e) {
            String msg = format("Unexpected error raised during provisioning of %s:\n%s", dropletName, e.getMessage());
            LOGGER.log(Level.WARNING,  msg, e);
            throw new AssertionError(msg);
        }
    }

    /**
     * Create a new {@link Slave} from the given {@link Droplet}
     * @param droplet the droplet being created
     * @param privateKey the RSA private key being used
     * @return the provisioned {@link Slave}
     * @throws IOException
     * @throws Descriptor.FormException
     */
    private Slave newSlave(ProvisioningActivity.Id provisioningId, String cloudName, Droplet droplet, String privateKey) throws IOException, Descriptor.FormException {
        LOGGER.log(Level.INFO, "Creating new slave...");
        return new Slave(
                provisioningId,
                cloudName,
                droplet.getName(),
                "DigitalOceanComputer running on DigitalOcean with name: " + droplet.getName(),
                droplet.getId(),
                privateKey,
                username,
                workspacePath,
                sshPort,
                numExecutors,
                idleTerminationInMinutes,
                labels,
                new DigitalOceanComputerLauncher(),
                new RetentionStrategy(),
                Collections.emptyList(),
                Util.fixNull(initScript)
        );
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<SlaveTemplate> {
        @Nonnull
        @Override
        public String getDisplayName() {
            return "Digital Ocean Slave Template";
        }

        public FormValidation doCheckName(@QueryParameter String name) {
            if (Strings.isNullOrEmpty(name)) {
                return FormValidation.error("Must be set");
            } else if (!DropletName.isValidSlaveName(name)) {
                return FormValidation.error("Must consist of A-Z, a-z, 0-9 and . symbols");
            } else {
                return FormValidation.ok();
            }
        }

        public FormValidation doCheckUsername(@QueryParameter String username) {
            if (Strings.isNullOrEmpty(username)) {
                return FormValidation.error("Must be set");
            } else {
                return FormValidation.ok();
            }
        }

        public FormValidation doCheckWorkspacePath(@QueryParameter String workspacePath) {
            if (Strings.isNullOrEmpty(workspacePath)) {
                return FormValidation.error("Must be set");
            } else {
                return FormValidation.ok();
            }
        }

        private static FormValidation doCheckNonNegativeNumber(String stringNumber) {
            if (Strings.isNullOrEmpty(stringNumber)) {
                return FormValidation.error("Must be set");
            } else {
                int number;

                try {
                    number = Integer.parseInt(stringNumber);
                } catch (Exception e) {
                    return FormValidation.error("Must be a number");
                }

                if (number < 0) {
                    return FormValidation.error("Must be a non-negative number");
                }

                return FormValidation.ok();
            }
        }

        public FormValidation doCheckSshPort(@QueryParameter String sshPort) {
            return doCheckNonNegativeNumber(sshPort);
        }

        public FormValidation doCheckNumExecutors(@QueryParameter String numExecutors) {
            if (Strings.isNullOrEmpty(numExecutors)) {
                return FormValidation.error("Must be set");
            } else {
                int number;

                try {
                    number = Integer.parseInt(numExecutors);
                } catch (Exception e) {
                    return FormValidation.error("Must be a number");
                }

                if (number <= 0) {
                    return FormValidation.error("Must be a positive number");
                }

                return FormValidation.ok();
            }
        }

        public FormValidation doCheckIdleTerminationInMinutes(@QueryParameter String idleTerminationInMinutes) {
            if (Strings.isNullOrEmpty(idleTerminationInMinutes)) {
                return FormValidation.error("Must be set");
            } else {
                try {
                    //noinspection ResultOfMethodCallIgnored
                    Integer.parseInt(idleTerminationInMinutes);
                } catch (Exception e) {
                    return FormValidation.error("Must be a number");
                }

                return FormValidation.ok();
            }
        }

        public FormValidation doCheckInstanceCap(@QueryParameter String instanceCap) {
            return doCheckNonNegativeNumber(instanceCap);
        }

        public FormValidation doCheckImageId(@RelativePath("..") @QueryParameter String authToken) {
            return DigitalOceanCloud.DescriptorImpl.doCheckAuthToken(authToken);
        }

        public FormValidation doCheckRegionId(@RelativePath("..") @QueryParameter String authToken) {
            return DigitalOceanCloud.DescriptorImpl.doCheckAuthToken(authToken);
        }

        public ListBoxModel doFillImageIdItems(@RelativePath("..") @QueryParameter String authToken) throws Exception {

            SortedMap<String, Image> availableImages = DigitalOcean.getAvailableImages(authToken);
            ListBoxModel model = new ListBoxModel();

            for (Map.Entry<String, Image> entry : availableImages.entrySet()) {
                final Image image = entry.getValue();

                // For non-snapshots, use the image ID instead of the slug (which isn't available anyway)
                // so that we can build images based upon backups.
                final String value = DigitalOcean.getImageIdentifier(image);

                LOGGER.log(Level.INFO, "doFillImageIdItems {0} => {1}", new Object[] {entry.getKey(), value});
                model.add(entry.getKey(), value);
            }

            return model;
        }

        public ListBoxModel doFillRegionIdItems(@RelativePath("..") @QueryParameter String authToken) throws Exception {

            List<Region> availableSizes = DigitalOcean.getAvailableRegions(authToken);
            ListBoxModel model = new ListBoxModel();

            for (Region region : availableSizes) {
                model.add(region.getName(), region.getSlug());
            }

            return model;
        }
    }

    @SuppressWarnings("unchecked")
    public Descriptor<SlaveTemplate> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
    }

    public String getName() {
        return name;
    }

    public String getRegionId() {
        return regionId;
    }

    public String getLabels() {
        return labels;
    }

    public String getLabelString() {
        return labelString;
    }

    public boolean isLabellessJobsAllowed() {
        return labellessJobsAllowed;
    }

    public Set<LabelAtom> getLabelSet() {
        return labelSet;
    }

    public String getImageId() {
        return imageId;
    }

    public String getUsername() {
        return username;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public int getNumExecutors() {
        return numExecutors;
    }

    public int getIdleTerminationInMinutes() {
        return idleTerminationInMinutes;
    }

    public int getInstanceCap() {
        return instanceCap;
    }

    public boolean isInstallMonitoring() {
        return installMonitoringAgent;
    }

    public String getTags() {
        return tags;
    }

    public String getUserData() {
        return userData;
    }

    public String getInitScript() {
        return initScript;
    }

    public Boolean isErroring() {
        if (null == errorTime) {
            return false;
        }
        final LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(errorTime.plusHours(1))) {
            // If an hour has passed since the error, consider the template not broken again.
            errorTime = null;
            return false;
        }
        return true;
    }

    public int getSshPort() {
        return sshPort;
    }

    private static int tryParseInteger(final String integerString, final int defaultValue) {
        try {
            return Integer.parseInt(integerString);
        }
        catch (NumberFormatException e) {
            LOGGER.log(Level.INFO, "Invalid integer {0}, defaulting to {1}", new Object[] {integerString, defaultValue});
            return defaultValue;
        }
    }

    protected Object readResolve() {
        labelSet = Label.parse(labels);
        return this;
    }

    public String toString() {
        return getName();
    }
}
