/*
 * Copyright (c) 2024 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package pt.impl;

import com.google.common.util.concurrent.FluentFuture;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.opendaylight.mdsal.binding.api.DataBroker;
import org.opendaylight.mdsal.binding.api.ReadTransaction;
import org.opendaylight.mdsal.binding.api.WriteTransaction;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.device.rev250611.Device;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.device.rev250611.DeviceBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.device.rev250611.Ipv4Address;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.device.rev250611.MacAddress;
import org.opendaylight.yangtools.binding.DataObjectIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockBindingProvider {

    private static final Logger LOG = LoggerFactory.getLogger(MockBindingProvider.class);

    private final DataBroker dataBroker;

    public MockBindingProvider(final DataBroker dataBroker) {
        this.dataBroker = dataBroker;
    }

    /**
     * Method called when the blueprint container is created.
     */
    public void init() throws ExecutionException, InterruptedException, UnknownHostException {
        LOG.info("MockBindingProvider Session Initiated");
        Device data = new DeviceBuilder().setHostname("Host1")
                .setIpAddress(Ipv4Address.getDefaultInstance("156.127.13.51"))
                .setMacAddress(MacAddress.getDefaultInstance("00:1A:2B:3C:4D:5E"))
                .setLocation("EU")
                .build();

        final WriteTransaction tx = dataBroker.newWriteOnlyTransaction();
        DataObjectIdentifier<Device> identifier = DataObjectIdentifier.builder(Device.class).build();
        tx.put(LogicalDatastoreType.CONFIGURATION, identifier, data);
        tx.commit().get();

        final ReadTransaction rx = dataBroker.newReadOnlyTransaction();
        FluentFuture<Optional<Device>> future = rx.read(LogicalDatastoreType.CONFIGURATION, identifier);
        Device dataOut = future.get().orElseThrow();
        LOG.info("Data Out is {}", dataOut);
    }

    /**
     * Method called when the blueprint container is destroyed.
     */
    public void close() {
        LOG.info("MockBindingProvider Closed");
    }
}