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

import com.myjeeva.digitalocean.exception.DigitalOceanException;
import com.myjeeva.digitalocean.exception.RequestUnsuccessfulException;
import com.myjeeva.digitalocean.impl.DigitalOceanClient;
import com.myjeeva.digitalocean.pojo.Droplet;
import hudson.slaves.AbstractCloudComputer;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * A {@link hudson.model.Computer} implementation for DigitalOcean. Holds a handle to an {@link Slave}.
 *
 * <p>Mainly responsible for updating the {@link Droplet} information via {@link Computer#updateInstanceDescription()}
 *
 * @author robert.gruendler@dubture.com
 */
public class Computer extends AbstractCloudComputer<Slave> implements TrackedItem {

    private static final Logger LOGGER = Logger.getLogger(Computer.class.getName());

    private final Slave slave;

    public Computer(Slave slave) {
        super(slave);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        Exception e = new Exception();
        e.printStackTrace(pw);
        String sStackTrace = sw.toString(); // stack trace as a string

        LOGGER.log(Level.WARNING, sStackTrace);


        LOGGER.info("computer created slave " + slave.getNodeName()  + " LOGGER is shit " + slave.getDropletId());
        this.slave = slave;
    }

    public Droplet updateInstanceDescription() throws RequestUnsuccessfulException, DigitalOceanException {
        DigitalOceanClient apiClient = new DigitalOceanClient(slave.getCloud().getAuthToken());
        return apiClient.getDropletInfo(slave.getDropletId());
    }

    @Override
    protected void onRemoved() {
        super.onRemoved();

        LOGGER.info("Slave removed, deleting droplet " + slave.getDropletId());
        DigitalOcean.tryDestroyDropletAsync(slave.getCloud().getAuthToken(), slave.getDropletId());
    }

    @Override
    public ProvisioningActivity.Id getId() {
        return slave.getId();
    }

    public Cloud getCloud() {
        return getNode().getCloud();
    }

    public int getSshPort() {
        return getNode().getSshPort();
    }

    public String getRemoteAdmin() {
        return getNode().getRemoteAdmin();
    }
    public long getStartTimeMillis() {
        return getNode().getStartTimeMillis();
    }

}
