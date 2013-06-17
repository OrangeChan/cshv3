// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
// 
//   http://www.apache.org/licenses/LICENSE-2.0
// 
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
// 
// Automatically generated by addcopyright.py at 01/29/2013
package com.cloud.baremetal.networkservice;

import com.cloud.baremetal.database.BaremetalDhcpVO;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.Pod;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PhysicalNetworkServiceProvider;
import com.cloud.network.element.DhcpServiceProvider;
import com.cloud.network.element.NetworkElement;
import com.cloud.offering.NetworkOffering;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.SearchCriteria2;
import com.cloud.utils.db.SearchCriteriaService;
import com.cloud.utils.db.Transaction;
import com.cloud.vm.*;
import com.cloud.vm.VirtualMachine.Type;
import com.cloud.vm.dao.NicDao;
import org.apache.log4j.Logger;

import javax.ejb.Local;
import javax.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Local(value = NetworkElement.class)
public class BaremetalDhcpElement extends AdapterBase implements DhcpServiceProvider {
    private static final Logger s_logger = Logger.getLogger(BaremetalDhcpElement.class);
    private static final Map<Service, Map<Capability, String>> capabilities;
    
    @Inject NicDao _nicDao;
    @Inject BaremetalDhcpManager _dhcpMgr;
    
    static {
        Capability cap = new Capability(BaremetalDhcpManager.BAREMETAL_DHCP_SERVICE_CAPABITLITY);
        Map<Capability, String> baremetalCaps = new HashMap<Capability, String>();
        baremetalCaps.put(cap, null);
        capabilities = new HashMap<Service, Map<Capability, String>>();
        capabilities.put(Service.Dhcp, baremetalCaps);
    }
    
    @Override
    public Map<Service, Map<Capability, String>> getCapabilities() {
        return capabilities;
    }

    @Override
    public Provider getProvider() {
        return BaremetalDhcpManager.BAREMETAL_DHCP_SERVICE_PROVIDER;
    }

    private boolean canHandle(DeployDestination dest, TrafficType trafficType, GuestType networkType) {
        Pod pod = dest.getPod();
        if (pod != null && dest.getDataCenter().getNetworkType() == NetworkType.Basic && trafficType == TrafficType.Guest) {
            SearchCriteriaService<BaremetalDhcpVO, BaremetalDhcpVO> sc = SearchCriteria2.create(BaremetalDhcpVO.class);
            sc.addAnd(sc.getEntity().getPodId(), Op.EQ, pod.getId());
            return sc.find() != null;
        }
        
        return false;
    }
    
    @Override
    public boolean implement(Network network, NetworkOffering offering, DeployDestination dest, ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        if (offering.isSystemOnly() || !canHandle(dest, offering.getTrafficType(), network.getGuestType())) {
            s_logger.debug("BaremetalDhcpElement can not handle networkoffering: " + offering.getName());
            return false;
        }
        return true;
    }

    @Override
    @DB
    public boolean prepare(Network network, NicProfile nic, VirtualMachineProfile<? extends VirtualMachine> vm, DeployDestination dest,
            ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        Host host = dest.getHost();
        if (vm.getType() != Type.User || vm.getHypervisorType() != HypervisorType.BareMetal) {
            return false;
        }
        
        Transaction txn = Transaction.currentTxn();
        txn.start();
        nic.setMacAddress(host.getPrivateMacAddress());
        NicVO vo = _nicDao.findById(nic.getId());
        assert vo != null : "Where ths nic " + nic.getId() + " going???";
        vo.setMacAddress(nic.getMacAddress());
        _nicDao.update(vo.getId(), vo);
        txn.commit();
        return true;
    }

    @Override
    public boolean release(Network network, NicProfile nic, VirtualMachineProfile<? extends VirtualMachine> vm, ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean shutdown(Network network, ReservationContext context, boolean cleanup) throws ConcurrentOperationException, ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean destroy(Network network, ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean isReady(PhysicalNetworkServiceProvider provider) {
        return true;
    }

    @Override
    public boolean shutdownProviderInstances(PhysicalNetworkServiceProvider provider, ReservationContext context) throws ConcurrentOperationException,
            ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean canEnableIndividualServices() {
        return false;
    }

    @Override
    public boolean verifyServicesCombination(Set<Service> services) {
        return true;
    }
    
    public boolean addDhcpEntry(Network network, NicProfile nic, VirtualMachineProfile<? extends VirtualMachine> vm, DeployDestination dest,
            ReservationContext context) throws ConcurrentOperationException, InsufficientCapacityException, ResourceUnavailableException {
        if (vm.getHypervisorType() != HypervisorType.BareMetal || !canHandle(dest, network.getTrafficType(), network.getGuestType())) {
            return false;
        }
        return _dhcpMgr.addVirtualMachineIntoNetwork(network, nic, vm, dest, context);
    }

    @Override
    public boolean configDhcpSupportForSubnet(Network network, NicProfile nic, VirtualMachineProfile<? extends VirtualMachine> vm, DeployDestination dest, ReservationContext context) throws ConcurrentOperationException, InsufficientCapacityException, ResourceUnavailableException {
        return false;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public boolean removeDhcpSupportForSubnet(Network network) {
        return false;  //To change body of implemented methods use File | Settings | File Templates.
    }

}
