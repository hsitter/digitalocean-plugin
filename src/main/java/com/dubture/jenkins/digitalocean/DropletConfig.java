/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017 Harald Sitter <sitter@kde.org>
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

import com.myjeeva.digitalocean.pojo.Size;
import com.thoughtworks.xstream.annotations.XStreamOmitField;
import hudson.Extension;
import hudson.RelativePath;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressWarnings("unused")
public class DropletConfig implements Describable<DropletConfig> {

    private static final Logger LOGGER = Logger.getLogger(Cloud.class.getName());

    private String sizeId;
    public String getSizeId() {
        return sizeId;
    }
    public void setSizeId(String sizeId) {
        this.sizeId = sizeId;
    }

    @XStreamOmitField
    private LocalDateTime errorTime;

    @DataBoundConstructor
    public DropletConfig(String sizeId) {
        this.sizeId = sizeId;
        this.errorTime = null;
    }

    public void setHadError() {
        LOGGER.info("now in error state " + sizeId);
        this.errorTime = LocalDateTime.now();
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

    @Extension
    public static final class DescriptorImpl extends Descriptor<DropletConfig> {

        @Override
        public String getDisplayName() {
            return null;
        }

        public FormValidation doCheckSizeId(@RelativePath("../..") @QueryParameter String authToken) {
            return Cloud.DescriptorImpl.doCheckAuthToken(authToken);
        }

        public ListBoxModel doFillSizeIdItems(@RelativePath("../..") @QueryParameter String authToken) throws Exception {

            LOGGER.log(Level.WARNING, "Config::doFillSizeIdItems " + authToken);

            List<Size> availableSizes = DigitalOcean.getAvailableSizes(authToken);
            ListBoxModel model = new ListBoxModel();

            for (Size size : availableSizes) {
                model.add(DigitalOcean.buildSizeLabel(size), size.getSlug());
            }

            return model;
        }
    }

    @SuppressWarnings("unchecked")
    public Descriptor<DropletConfig> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
    }

}
