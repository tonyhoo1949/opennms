//
// This file is part of the OpenNMS(R) Application.
//
// OpenNMS(R) is Copyright (C) 2002-2003 Blast Internet Services, Inc.  All rights reserved.
// OpenNMS(R) is a derivative work, containing both original code, included code and modified
// code that was published under the GNU General Public License. Copyrights for modified 
// and included code are below.
//
// OpenNMS(R) is a registered trademark of Blast Internet Services, Inc.
//
// Modifications:
//
// 2003 Nov 11: Merged changes from Rackspace project
// 2003 Jan 31: Cleaned up some unused imports.
//
// Original code base Copyright (C) 1999-2001 Oculan Corp.  All rights reserved.
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.                                                            
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
//       
// For more information contact: 
//      OpenNMS Licensing       <license@opennms.org>
//      http://www.opennms.org/
//      http://www.blast.com/
//
// Tab Size = 8
//

package org.opennms.netmgt.capsd;

import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;

import org.apache.log4j.Category;
import org.opennms.core.queue.FifoQueue;
import org.opennms.core.utils.ThreadCategory;
import org.opennms.netmgt.EventConstants;
import org.opennms.netmgt.config.CapsdConfigFactory;
import org.opennms.netmgt.config.DatabaseConnectionFactory;
import org.opennms.netmgt.config.OpennmsServerConfigFactory;
import org.opennms.netmgt.eventd.EventIpcManagerFactory;
import org.opennms.netmgt.eventd.EventListener;
import org.opennms.netmgt.utils.XmlrpcUtil;
import org.opennms.netmgt.xml.event.Event;
import org.opennms.netmgt.xml.event.Parm;
import org.opennms.netmgt.xml.event.Parms;
import org.opennms.netmgt.xml.event.Value;

/**
 *
 * @author <a href="mailto:jamesz@blast.com">James Zuo</a>
 * @author <a href="mailto:weave@oculan.com">Brian Weaver</a>
 * @author <a href="http://www.opennms.org/">OpenNMS</a>
 */
final class BroadcastEventProcessor
	implements EventListener
{
	/**
	 * SQL query to retrieve nodeid of a particulary interface address
	 */
	private static String 	SQL_RETRIEVE_NODEID = "select nodeid from ipinterface where ipaddr=? and isManaged!='D'";
	
        /**
         * SQL statement used to query the 'node' and 'ipinterface' tables to verify if a 
         * specified ipaddr and node label have already exist in the database.
         */
        private static String SQL_QUERY_IPINTERFACE_EXIST = "SELECT nodelabel, ipaddr FROM node, ipinterface WHERE node.nodeid = ipinterface.nodeid AND node.nodelabel = ? AND ipinterface.ipaddr = ? AND isManaged !='D' AND nodeType !='D'";
        
        /**
         * SQL statement used to query if a node with the specified nodelabel exist in 
         * the database, and the nodeid from the database if exists.
         */
        private static String SQL_QUERY_NODE_EXIST = "SELECT nodeid, dpname FROM node WHERE nodelabel = ? AND nodeType !='D'"; 

        /**
         * SQL statement used to verify if an ifservice with the specified ip address and 
         * service name exists in the database.
         */
        private static String SQL_QUERY_SERVICE_EXIST = "SELECT nodeid FROM ifservices, service WHERE ifservices.serviceid = service.serviceid AND ipaddr = ? AND servicename = ? AND status !='D'"; 

        /**
         * SQL statement used to verify if an ipinterface with the specified ip address 
         * exists in the database and retrieve the nodeid if exists.
         */
        private static String SQL_QUERY_IPADDRESS_EXIST = "SELECT nodeid FROM ipinterface WHERE ipaddr = ? AND isManaged !='D'";

        /**
         * SQL statement used to retrieve the serviced id from the database with a specified
         * service name.
         */
        private static String SQL_RETRIEVE_SERVICE_ID = "SELECT serviceid FROM service WHERE servicename = ?";

        /**
         * SQL statement used to delete all the ipinterfaces with a specified nodeid.
         */
        private static String SQL_DELETE_ALL_INTERFACES_ON_NODE = "DELETE FROM ipinterface WHERE nodeid = ?";

        /**
         * SQL statement used to delete an ipinterfac with a specified nodeid and ipaddress.
         */
        private static String SQL_DELETE_INTERFACE = "DELETE FROM ipinterface WHERE nodeid = ? AND ipaddr = ?";

        /**
         * SQL statement used to delete a node from the database with a specified nodelabel.
         */
        private static String SQL_DELETE_NODEID = "DELETE FROM node WHERE nodeid = ?";

        /**
         * SQL statement used to delete all usersNotified from the database with 
         * a specified nodeid.
         */
         private static String SQL_DELETE_USERSNOTIFIED_ON_NODE = "DELETE FROM usersNotified WHERE notifyID IN ( SELECT notifyID from notifications WHERE nodeid = ?)";

        /**
         * SQL statement used to delete all notifications from the database with 
         * a specified nodeid.
         */
         private static String SQL_DELETE_NOTIFICATIONS_ON_NODE = "DELETE FROM notifications WHERE nodeid = ?";

        /**
         * SQL statement used to delete all outages from the database with 
         * a specified nodeid.
         */
         private static String SQL_DELETE_OUTAGES_ON_NODE = "DELETE FROM outages WHERE nodeid = ?";

        /**
         * SQL statement used to delete all events from the database with 
         * a specified nodeid.
         */
         private static String SQL_DELETE_EVENTS_ON_NODE = "DELETE FROM events WHERE nodeid = ?";

        /**
         * SQL statement used to delete all ifservices from the database with 
         * a specified nodeid.
         */
         private static String SQL_DELETE_IFSERVICES_ON_NODE = "DELETE FROM ifservices WHERE nodeid = ?";

        /**
         * SQL statement used to delete all snmpinterface from the database with 
         * a specified nodeid.
         */
         private static String SQL_DELETE_SNMPINTERFACE_ON_NODE = "DELETE FROM snmpinterface WHERE nodeid = ?";

        /**
         * SQL statement used to delete all assets from the database with 
         * a specified nodeid.
         */
         private static String SQL_DELETE_ASSETS_ON_NODE = "DELETE FROM assets WHERE nodeid = ?";

        /**
         * SQL statement used to delete all usersnotified info associated with a specified interface 
         * from the database.
         */
         private static String SQL_DELETE_USERSNOTIFIED_ON_INTERFACE = "DELETE FROM usersnotified WHERE notifyid IN " +
                                                                    "(SELECT notifid FROM notifications " +
                                                                    " WHERE nodeid = ? AND interfaceid = ?)";
        /**
         * SQL statement used to delete all notifications associated with a specified interface 
         * from the database.
         */
         private static String SQL_DELETE_NOTIFICATIONS_ON_INTERFACE = "DELETE FROM notifications " +
                                                                    "WHERE nodeid = ? AND interfaceid = ?";
        
        /**
         * SQL statement used to delete all notifications associated with a specified interface 
         * from the database.
         */
         private static String SQL_DELETE_OUTAGES_ON_INTERFACE = "DELETE FROM outages " +
                                                                 "WHERE nodeid = ? AND ipaddr = ?";
        /**
         * SQL statement used to delete all events associated with a specified interface 
         * from the database.
         */
         private static String SQL_DELETE_EVENTS_ON_INTERFACE = "DELETE FROM events " +
                                                                "WHERE nodeid = ? AND ipaddr = ?";
        
        /**
         * SQL statement used to delete the snmpinterface entry associated with a specified interface 
         * from the database.
         */
         private static String SQL_DELETE_SNMPINTERFACE_ON_INTERFACE = "DELETE FROM snmpinterface " +
                                                                       "WHERE nodeid = ? AND ipaddr = ?";
        
        /**
         * SQL statement used to query if an interface is the snmp primary interface of a node.
         */
         private static String SQL_QUERY_PRIMARY_INTERFACE = "SELECT isSnmpPrimary FROM ipinterface " +
                                                             "WHERE nodeid = ? AND ipaddr = ?";
        
        /**
         * SQL statement used to delete all ifservices from the database with 
         * a specified interface.
         */
         private static String SQL_DELETE_IFSERVICES_ON_INTERFACE = "DELETE FROM ifservices WHERE nodeid = ? AND ipaddr = ?";

        /**
         * SQL statement used to query if an interface/server mapping already exists in the database.
         */
         private static String SQL_QUERY_INTERFACE_ON_SERVER = "SELECT * FROM serverMap WHERE ipaddr = ? AND servername = ?";

        /**
         * SQL statement used to query if an interface/service mapping already exists in the database.
         */
         private static String SQL_QUERY_SERVICE_MAPPING_EXIST = "SELECT * FROM serviceMap WHERE ipaddr = ? AND servicemapname = ?";

        /**
         * SQL statement used to delete an interface/server mapping from the database.
         */
         private static String SQL_DELETE_INTERFACE_ON_SERVER = "DELETE FROM serverMap WHERE ipaddr = ? AND servername = ?";

        /**
         * SQL statement used to add an interface/server mapping into the database;
         */
         private static String SQL_ADD_INTERFACE_TO_SERVER = "INSERT INTO serverMap VALUES (?, ?)";

        /**
         * SQL statement used to add an interface/service mapping into the database.
         */
         private static String SQL_ADD_SERVICE_TO_MAPPING = "INSERT INTO serviceMap VALUES (?, ?)";

        /**
         * SQL statement used to delete all services mapping to a specified interface from the database.
         */
         private static String SQL_DELETE_ALL_SERVICES_INTERFACE_MAPPING = "DELETE FROM serviceMap WHERE ipaddr = ?";

        /**
         * SQL statement used to delete an interface/service mapping from the database.
         */
         private static String SQL_DELETE_SERVICE_INTERFACE_MAPPING = "DELETE FROM serviceMap WHERE ipaddr = ? AND servicemapname = ?";

        /**
         * SQL statement used to count all the interface on a node
         */
         private static String SQL_COUNT_INTERFACES_ON_NODE = "SELECT count(ipaddr) FROM ipinterface WHERE nodeid = (SELECT nodeid FROM node WHERE nodelabel = ?) ";
         
        /**
	 * The location where suspectInterface events are enqueued
	 * for processing.
	 */
	private FifoQueue	m_suspectQ;

	/**
	 * The Capsd rescan scheduler
	 */
	private Scheduler 	m_scheduler;

        /**
         * Boolean flag to indicate if need to notify external xmlrpc server with
         * event processing failure.
         */
        private boolean         m_xmlrpc = false;

        /**
         * local openNMS server name
         */
        private String          m_localServer = null;
        
	/**
	 * Create message selector to set to the subscription
	 */
	private void createMessageSelectorAndSubscribe()
	{
		// Create the selector for the ueis this service is interested in
		//
		List ueiList = new ArrayList();

		// newSuspectInterface
		ueiList.add(EventConstants.NEW_SUSPECT_INTERFACE_EVENT_UEI);

		// forceRescan
		ueiList.add(EventConstants.FORCE_RESCAN_EVENT_UEI);

                // addNode
                ueiList.add(EventConstants.ADD_NODE_EVENT_UEI);
                
                // deleteNode
                ueiList.add(EventConstants.DELETE_NODE_EVENT_UEI);
		
                // addInterface
                ueiList.add(EventConstants.ADD_INTERFACE_EVENT_UEI);

                // deleteInterface
                ueiList.add(EventConstants.DELETE_INTERFACE_EVENT_UEI);
                
                // changeService
                ueiList.add(EventConstants.CHANGE_SERVICE_EVENT_UEI);

		// updateServer
		ueiList.add(EventConstants.UPDATE_SERVER_EVENT_UEI);

		// updateService
		ueiList.add(EventConstants.UPDATE_SERVICE_EVENT_UEI);
                
		// nodeAdded
		ueiList.add(EventConstants.NODE_ADDED_EVENT_UEI);

		// nodeDeleted
		ueiList.add(EventConstants.NODE_DELETED_EVENT_UEI);

		// duplicateNodeDeleted
		ueiList.add(EventConstants.DUP_NODE_DELETED_EVENT_UEI);

		EventIpcManagerFactory.init();
		EventIpcManagerFactory.getInstance().getManager().addEventListener(this, ueiList);
	}

	/**
	 * Constructor 
	 *
	 * @param suspectQ	The queue where new SuspectEventProcessor objects 
	 *                      are enqueued for running..
	 * @param scheduler	Rescan scheduler.
	 */
	BroadcastEventProcessor(FifoQueue suspectQ, Scheduler scheduler)
	{
		// Suspect queue
		//
		m_suspectQ = suspectQ;

		// Scheduler
		//
		m_scheduler = scheduler;

                // If need to notify external xmlrpc server
                m_xmlrpc = CapsdConfigFactory.getInstance().getXmlrpc().equals("true");

                // the local servername
                m_localServer = OpennmsServerConfigFactory.getInstance().getServerName();
                
		// Subscribe to eventd
		//
		createMessageSelectorAndSubscribe();
	}
        

        /**
         * Get the local server name
         */
        public String getLocalServer()
        {
                return m_localServer;
        }
        
	/**
	 * Unsubscribe from eventd
	 */
	public void close()
	{
		EventIpcManagerFactory.getInstance().getManager().removeEventListener(this);
	}

	/**
	 * Process the event,  add a node with the specified node label and interface 
         * to the database
	 *
	 * @param event	The event to process.
	 */
	private void addNodeHandler(Event event)
	{
	        String ipaddr = event.getInterface();
                String sourceUei = event.getUei();

                Category log = ThreadCategory.getInstance(getClass());
		if (log.isDebugEnabled())
			log.debug("addNodeHandler:  processing addNode event for " + ipaddr);
			
		// Extract node label and transaction No. from the event parms
		String nodeLabel = null;
                long txNo = -1L;
		Parms parms = event.getParms();
		if (parms != null)
		{
			String parmName = null;
			Value parmValue = null;
			String parmContent = null;
		
			Enumeration parmEnum = parms.enumerateParm();
			while(parmEnum.hasMoreElements())
			{
				Parm parm = (Parm)parmEnum.nextElement();
				parmName  = parm.getParmName();
				parmValue = parm.getValue();
				if (parmValue == null)
					continue;
				else 
					parmContent = parmValue.getContent();
	
				//  get node label
				if (parmName.equals(EventConstants.PARM_NODE_LABEL))
				{
					nodeLabel = parmContent;
		                        if (log.isDebugEnabled())
			                        log.debug("addNodeHandler:  parmName: " + parmName
                                                        + " /parmContent: " + parmContent);
				}
				else if (parmName.equals(EventConstants.PARM_TRANSACTION_NO))
                                {
                                        String temp = parmContent;
		                        if (log.isDebugEnabled())
			                        log.debug("addNodeHandler:  parmName: " + parmName
                                                        + " /parmContent: " + parmContent);
                                        try
                                        {
                                                txNo = Long.valueOf(temp).longValue();
                                        }
                                        catch (NumberFormatException nfe)
                                        {
                                                log.warn("addNodeHandler: Parameter " + EventConstants.PARM_TRANSACTION_NO 
                                                        + " cannot be non-numberic", nfe);
                                                txNo = -1L;
                                        }
                                }
			}
		}

                boolean invalidParameters = ((ipaddr == null) || (nodeLabel == null));
                if (m_xmlrpc)
                        invalidParameters = invalidParameters || (txNo == -1L);
                
                if (invalidParameters)
                {
		        if (log.isDebugEnabled())
		                log.debug("addNodeHandler:  Invalid parameters." );

                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, sourceUei, "Invalid parameters", 
                                        status, "OpenNMS.Capsd");
                        }
                        
			return;
		}
                
                java.sql.Connection dbConn = null;
		PreparedStatement stmt = null;
		try
		{
			dbConn = DatabaseConnectionFactory.getInstance().getConnection();
		
			stmt = dbConn.prepareStatement(SQL_QUERY_IPINTERFACE_EXIST);
	
			stmt.setString(1, nodeLabel);
			stmt.setString(2, ipaddr);
	
			ResultSet rs = stmt.executeQuery();
			log.debug("addNodeHandler: node " + nodeLabel + " with IPAddress " 
                                    + ipaddr  + " progressing to the checkpoint.");
			while(rs.next())
			{
				if (log.isDebugEnabled())
				{
					log.debug("addNodeHandler: node " + nodeLabel + " with IPAddress " 
                                        + ipaddr + " already exist in the database.");
				}
                                
                                if (m_xmlrpc)
                                {
                                        int status = EventConstants.XMLRPC_NOTIFY_SUCCESS;
                                        XmlrpcUtil.createAndSendXmlrpcNotificationEvent( txNo, 
                                                                              sourceUei, 
                                                                              "Node allready exist.",
                                                                              status,
                                                                              "OpenNMS.Capsd");
                                }
			        return;
			}

                        // the node does not exist in the database. Add the node with the specified
                        // node label and add the ipaddress to the database.
                        addNode(dbConn, nodeLabel, ipaddr, txNo, sourceUei);

		}
		catch(SQLException sqlE)
		{
			log.error("addNodeHandler: SQLException during add node and ipaddress to tables", sqlE);
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, sourceUei, 
                                                        sqlE.getMessage(), status, "OpenNMS.Capsd");
                        }
		}
		catch(java.net.UnknownHostException e)
		{
			log.error("addNodeHandler: can not solve unknow host.", e);
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, sourceUei, e.getMessage(), 
                                        status, "OpenNMS.Capsd"); 
                        }
		}
		finally
		{
			// close the statement
			if (stmt != null)
				try { stmt.close(); } catch(SQLException sqlE) { };

			// close the connection
			if (dbConn != null)
				try { dbConn.close(); } catch(SQLException sqlE) { };					
		}
		
	}


	/**
	 * This method add a node with the specified node label and the secified
         * IP address to the database.
	 *
	 * @param conn 	        The JDBC Database connection.
         * @param nodeLabel     the node label to identify the node to create. 
         * @param ipaddr        the ipaddress to be added into the ipinterface table.
         * @param txNo          the transaction no.
         * @param callerUei     the uei of the caller event
	 */
	private void addNode(java.sql.Connection conn, String nodeLabel, String ipaddr, long txNo, String callerUei)
	        throws SQLException, java.net.UnknownHostException	
	{
		Category log = ThreadCategory.getInstance(getClass());
		
                if (nodeLabel == null | ipaddr == null)
			return;
		
                if (log.isDebugEnabled())
			log.debug("addNode:  Add a node " + nodeLabel + " to the database");
                        
                DbNodeEntry node = DbNodeEntry.create();
                Date now = new Date();
                node.setCreationTime(now);
                node.setNodeType(DbNodeEntry.NODE_TYPE_ACTIVE);
                node.setLabel(nodeLabel);
                node.setLabelSource(DbNodeEntry.LABEL_SOURCE_USER);
                node.store(conn);

                createAndSendNodeAddedEvent(node, txNo, callerUei);
                if (m_xmlrpc)
                {
                        int status = EventConstants.XMLRPC_NOTIFY_SUCCESS;
                        XmlrpcUtil.createAndSendXmlrpcNotificationEvent( txNo, 
                                                              callerUei, 
                                                              "Successfully added node" + nodeLabel,
                                                              status,
                                                              "OpenNMS.Capsd");
                }

		if (log.isDebugEnabled())
			log.debug("addNode:  Add an IP Address " + ipaddr + " to the database");
                        
                // add the ipaddess to the database
                InetAddress ifaddress = InetAddress.getByName(ipaddr);
                DbIpInterfaceEntry ipInterface = DbIpInterfaceEntry.create(node.getNodeId(), ifaddress); 
                ipInterface.setHostname(ifaddress.getHostName());
                ipInterface.setManagedState(DbIpInterfaceEntry.STATE_MANAGED);
                ipInterface.setPrimaryState(DbIpInterfaceEntry.SNMP_NOT_ELIGIBLE);
                ipInterface.store(conn);

                createAndSendNodeGainedInterfaceEvent(node, ifaddress, txNo, callerUei);
                if (m_xmlrpc)
                {
                        int status = EventConstants.XMLRPC_NOTIFY_SUCCESS;
                        String message = new String("Successfully added interface: ") + ipaddr 
                                                        + " to node: " + nodeLabel;
                        XmlrpcUtil.createAndSendXmlrpcNotificationEvent( txNo, 
                                                              callerUei, 
                                                              message,
                                                              status,
                                                              "OpenNMS.Capsd");
                }
        }

       
        /**
         * This method is responsible for generating a nodeAdded event and sending
         * it to eventd..
         *
         * @param nodeEntry     The node Added.
         * @param txNo          the transaction no.
         * @param callerUei     the Uei of the caller event.
         *
         */
        private void createAndSendNodeAddedEvent(DbNodeEntry nodeEntry, long txNo, String callerUei)
        {
		Category log = ThreadCategory.getInstance(getClass());
		if (log.isDebugEnabled())
			log.debug("createAndSendNodeAddedEvent:  nodeId  " + nodeEntry.getNodeId());
        
                Event newEvent = new Event();
                newEvent.setUei(EventConstants.NODE_ADDED_EVENT_UEI);
                newEvent.setSource("OpenNMS.Capsd");
                newEvent.setNodeid(nodeEntry.getNodeId());
                newEvent.setHost(Capsd.getLocalHostAddress());
                newEvent.setTime(EventConstants.formatToString(new java.util.Date()));

                // Add appropriate parms
                Parms eventParms = new Parms();
                Parm eventParm = null;
                Value parmValue = null;

                eventParm = new Parm();
                eventParm.setParmName(EventConstants.PARM_NODE_LABEL);
                parmValue = new Value();
                parmValue.setContent(nodeEntry.getLabel());
                eventParm.setValue(parmValue);
                eventParms.addParm(eventParm);

                // Add node label source
                eventParm = new Parm();
                eventParm.setParmName(EventConstants.PARM_NODE_LABEL_SOURCE);
                parmValue = new Value();
                char labelSource[] = new char[] {nodeEntry.getLabelSource()};
                parmValue.setContent(new String(labelSource));
                eventParm.setValue(parmValue);
                eventParms.addParm(eventParm);
                
                
                // Add Parms to the event
                newEvent.setParms(eventParms);

                // Send event to Eventd
                try
                {
                        EventIpcManagerFactory.getInstance().getManager().sendNow(newEvent);

                        if (log.isDebugEnabled())
                                log.debug("createdAndSendNodeAddedEvent: successfully sent nodeAdded event for nodeId: " 
                                        + nodeEntry.getNodeId());
                }
                catch(Throwable t)
                {
                        log.warn("run: unexpected throwable exception caught during send to middleware", t);
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, callerUei, 
                                        "caught unexpected throwable exception.", status, "OpenNMS.Capsd");
                        }
                }
        }                
	
	
        /**
         * This method is responsible for generating a nodeGainedInterface event and sending
         * it to eventd..
         *
         * @param nodeEntry     The node that gained the interface.
         * @param ifaddr        the interface gained on the node.
         * @param txNo          the transaction no.
         * @param callerUei     the uei of the caller event.
         *
         */
        private void createAndSendNodeGainedInterfaceEvent(DbNodeEntry nodeEntry, 
                        InetAddress ifaddr, long txNo, String callerUei)
        {
		Category log = ThreadCategory.getInstance(getClass());
		if (log.isDebugEnabled())
			log.debug("createAndSendNodeAddedEvent:  nodeId  " + nodeEntry.getNodeId());
        
                Event newEvent = new Event();
                newEvent.setUei(EventConstants.NODE_GAINED_INTERFACE_EVENT_UEI);
                newEvent.setSource("OpenNMS.Capsd");
                newEvent.setNodeid(nodeEntry.getNodeId());
                newEvent.setHost(Capsd.getLocalHostAddress());
                newEvent.setInterface(ifaddr.getHostAddress());
                newEvent.setTime(EventConstants.formatToString(new java.util.Date()));

                // Add appropriate parms
                Parms eventParms = new Parms();
                Parm eventParm = null;
                Value parmValue = null;

                // Add IP host name
                eventParm = new Parm();
                eventParm.setParmName(EventConstants.PARM_IP_HOSTNAME);
                parmValue = new Value();
                parmValue.setContent(ifaddr.getHostName());
                eventParm.setValue(parmValue);
                eventParms.addParm(eventParm);

                eventParms.addParm(eventParm);
                
                
                // Add Parms to the event
                newEvent.setParms(eventParms);

                // Send event to Eventd
                try
                {
                        EventIpcManagerFactory.getInstance().getManager().sendNow(newEvent);

                        if (log.isDebugEnabled())
                                log.debug("createdAndSendNodeGainedInterfaceEvent: successfully sent nodeGainedInterface event "
                                        + "for interface: " + ifaddr.getHostAddress() + " on nodeId: " 
                                        + nodeEntry.getNodeId());
                }
                catch(Throwable t)
                {
                        log.warn("run: unexpected throwable exception caught during send to middleware", t);
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, callerUei, 
                                        "caught unexpected throwable exception.", status, "OpenNMS.Capsd");
                        }
                }
        }                
	
        /** 
	 * This method is responsible for:
         * 1. removing the node specified in the deleteNode event from the database.
         * 2. delete all IP addresses associated with this node from the database.
         * 3. delete all services being polled from this node from the database
         * 4. issue an nodeDeleted event so that this node will be removed from the 
         *    Poller's pollable node map, and all the servies polling from this node 
         *    shall be stopped.
         * 5. delete all info associated with this node from the database, such as
         *    notifications, events, outages etc.
         */
	private void deleteNodeHandler(Event event)
	{
		Category log = ThreadCategory.getInstance(getClass());
                String sourceUei = event.getUei();
                
		// Extract node label and transaction No. from the event parms
		String nodeLabel = null;
                long txNo = -1L;
                
		String transaction = null;
		Parms parms = event.getParms();
		int nodeid = (int)event.getNodeid();

		if (nodeid < 1)
			nodeid = -1;

		if (parms != null)
		{
			String parmName = null;
			Value parmValue = null;
			String parmContent = null;
		
			Enumeration parmEnum = parms.enumerateParm();
			while(parmEnum.hasMoreElements())
			{
				Parm parm = (Parm)parmEnum.nextElement();
				parmName  = parm.getParmName();
				parmValue = parm.getValue();
				if (parmValue == null)
					continue;
				else 
					parmContent = parmValue.getContent();
	
				//  get node label
				if (parmName.equals(EventConstants.PARM_NODE_LABEL))
				{
					nodeLabel = parmContent;
				}
				else if (parmName.equals(EventConstants.PARM_TRANSACTION_NO))
                                {
                                        transaction = parmContent;
		                        if (log.isDebugEnabled())
			                        log.debug("deleteNodeHandler:  parmName: " + parmName
                                                        + " /parmContent: " + parmContent);
					if (!transaction.equals("webUI"))
					{
                                        	try
                                        	{
                                                	txNo = Long.valueOf(transaction).longValue();
                                        	}
                                        	catch (NumberFormatException nfe)
                                        	{
                                                	log.warn("deleteNodeHandler: Parameter " + EventConstants.PARM_TRANSACTION_NO 
                                                      	 	 + " cannot be non-numeric", nfe);
                                                	txNo = -1L;
                                        	}
                                	}
                                }
						
			}
		}
                
                boolean invalidParameters = (nodeLabel == null);
                if (m_xmlrpc)
                        invalidParameters = invalidParameters || (txNo == -1L);
                        
		if ((invalidParameters) && (!transaction.equals("webUI")))
                {
		        if (log.isDebugEnabled())
		                log.debug("deleteNodeHandler:  Invalid parameters." );
                                
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, sourceUei, 
                                        "Invalid parameters", status, "OpenNMS.Capsd");
                        }
                        
			return;
		}
                
		if (log.isDebugEnabled())
			log.debug("deleteNodeHandler: deleting node: " + nodeLabel);
		
                java.sql.Connection dbConn = null;
		PreparedStatement stmt = null;
		try
		{
			dbConn = DatabaseConnectionFactory.getInstance().getConnection();
		        
                        // First, verify if the node exists in database, and retrieve
                        // nodeid if exists.

			if (nodeid == -1)
			{
				stmt = dbConn.prepareStatement(SQL_QUERY_NODE_EXIST);
	
				stmt.setString(1, nodeLabel);
                        
				ResultSet rs = stmt.executeQuery();
				while(rs.next())
				{
                                	nodeid = rs.getInt(1);
                        	}
		        
                        	if (nodeid == -1)  // Sanity check
		        	{
			        	log.error("DeleteNode: There is no node with node label: " + nodeLabel + " exists in the database.");
                                	int status = EventConstants.XMLRPC_NOTIFY_SUCCESS;
                                	XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, sourceUei, 
                                        	"Node does not exist in the database", status, "OpenNMS.Capsd");
			        	return;
		        	}
			}
		
			if (log.isDebugEnabled())
				log.debug("deleteNodeHandler: Starting delete of nodeid: " + nodeid);
                
	                // Deleting all the userNotified info associated with the nodeid
			stmt = dbConn.prepareStatement(SQL_DELETE_USERSNOTIFIED_ON_NODE);
			stmt.setInt(1, nodeid);
			stmt.executeUpdate();
			if (log.isDebugEnabled())
				log.debug("deleteNodeHandler: deleted all usersNotified info on  nodeid: " + nodeid);

	                // Deleting all the notifications associated with the nodeid
			stmt = dbConn.prepareStatement(SQL_DELETE_NOTIFICATIONS_ON_NODE);
			stmt.setInt(1, nodeid);
			stmt.executeUpdate();
			if (log.isDebugEnabled())
				log.debug("deleteNodeHandler: deleted all notifications on  nodeid: " + nodeid);

	                // Deleting all the outages associated with the nodeid
			stmt = dbConn.prepareStatement(SQL_DELETE_OUTAGES_ON_NODE);
			stmt.setInt(1, nodeid);
			stmt.executeUpdate();
			if (log.isDebugEnabled())
				log.debug("deleteNodeHandler: deleted all outages on  nodeid: " + nodeid);

	                // Deleting all the events associated with the nodeid
			stmt = dbConn.prepareStatement(SQL_DELETE_EVENTS_ON_NODE);
			stmt.setInt(1, nodeid);
			stmt.executeUpdate();
			if (log.isDebugEnabled())
				log.debug("deleteNodeHandler: deleted all events on  nodeid: " + nodeid);

	                // Deleting all the ifservices associated with the nodeid
			stmt = dbConn.prepareStatement(SQL_DELETE_IFSERVICES_ON_NODE);
			stmt.setInt(1, nodeid);
			stmt.executeUpdate();
			if (log.isDebugEnabled())
				log.debug("deleteNodeHandler: deleted all ifservices on  nodeid: " + nodeid);

	                // Deleting all the ipaddresses associated with the nodeid
			stmt = dbConn.prepareStatement(SQL_DELETE_ALL_INTERFACES_ON_NODE);
			stmt.setInt(1, nodeid);
			stmt.executeUpdate();
			if (log.isDebugEnabled())
                        {
				log.debug("deleteNodeHandler: deleted all ipaddresses on  nodeid: " + nodeid);
                        
			}
	                
	                // Deleting all the snmpInterfaces associated with the nodeid
			stmt = dbConn.prepareStatement(SQL_DELETE_SNMPINTERFACE_ON_NODE);
			stmt.setInt(1, nodeid);
			stmt.executeUpdate();
			if (log.isDebugEnabled())
				log.debug("deleteNodeHandler: deleted all snmpinterfaces on  nodeid: " + nodeid);
                        
	                // Deleting all the assets associated with the nodeid
			stmt = dbConn.prepareStatement(SQL_DELETE_ASSETS_ON_NODE);
			stmt.setInt(1, nodeid);
			stmt.executeUpdate();
			if (log.isDebugEnabled())
				log.debug("deleteNodeHandler: deleted all assets on  nodeid: " + nodeid);
                        
                        // Deleting the node from the database 
			stmt = dbConn.prepareStatement(SQL_DELETE_NODEID);
			stmt.setInt(1, nodeid);
			stmt.executeUpdate();
			if (log.isDebugEnabled())
				log.debug("deleteNodeHandler: deleted the node with node label: " + nodeLabel);
                        
                        // Create a nodeDeleted event and send it to eventd, this new event will remove all
                        // the services and interfaces associated with the specified node from the pollable
                        // node list, so the poller will stop to poll any service on this node.
                        createAndSendNodeDeletedEvent(nodeid, event.getHost(), nodeLabel, txNo, sourceUei);
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_SUCCESS;
                                String message = new String("Successfully deleted node with node label: ")  + nodeLabel;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, sourceUei, message, 
                                        status, "OpenNMS.Capsd"); 
                        }

                }
		catch(SQLException sqlE)
		{
			log.error("SQLException during add node and ipaddress to tables", sqlE);
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, sourceUei, 
                                        sqlE.getMessage(), status, "OpenNMS.Capsd"); 
                        }
		}
		finally
		{
			// close the statement
			if (stmt != null)
				try { stmt.close(); } catch(SQLException sqlE) { };

			// close the connection
			if (dbConn != null)
				try { dbConn.close(); } catch(SQLException sqlE) { };					
		}
                
	}
       
        /**
         * This method is responsible for generating a nodeDeleted event and sending
         * it to eventd..
         *
         * @param nodeId        Nodeid of the node got deleted.
         * @param hostName      the Host server name. 
         * @param nodeLabel     the node label of the deleted node.
         * @param callerUei     the uei of the caller event.
         */
        private void createAndSendNodeDeletedEvent( int nodeId, String hostName, String nodeLabel, 
                                                    long txNo, String callerUei)
        {
		Category log = ThreadCategory.getInstance(getClass());
		if (log.isDebugEnabled())
			log.debug("createAndSendNodeDeletedEvent:  processing deleteNode event for nodeid:  " + nodeId);
        
                Event newEvent = new Event();
                newEvent.setUei(EventConstants.NODE_DELETED_EVENT_UEI);
                newEvent.setSource("OpenNMS.Capsd");
                newEvent.setNodeid(nodeId);
                newEvent.setHost(hostName);
                newEvent.setTime(EventConstants.formatToString(new java.util.Date()));

                // Add appropriate parms
                Parms eventParms = new Parms();
                Parm eventParm = null;
                Value parmValue = null;

                eventParm = new Parm();
                eventParm.setParmName(EventConstants.PARM_NODE_LABEL);
                parmValue = new Value();
                parmValue.setContent(nodeLabel);
                eventParm.setValue(parmValue);
                eventParms.addParm(eventParm);

                eventParm = new Parm();
                eventParm.setParmName(EventConstants.PARM_TRANSACTION_NO);
                parmValue = new Value();
                parmValue.setContent((new Long(txNo)).toString());
                eventParm.setValue(parmValue);
                eventParms.addParm(eventParm);
                
                // Add Parms to the event
		if ((nodeLabel != null) && (((new Long(txNo)).toString()) != null))
                	newEvent.setParms(eventParms);

                // Send event to Eventd
                try
                {
                        EventIpcManagerFactory.getInstance().getManager().sendNow(newEvent);

                        if (log.isDebugEnabled())
                                log.debug("createdAndSendNodeDeletedEvent: successfully sent nodeDeleted event for node: " 
                                        + nodeLabel);
                }
                catch(Throwable t)
                {
                        log.warn("run: unexpected throwable exception caught during send to middleware", t);
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, callerUei, 
                                                "caught unexpected throwable exception.", status, "OpenNMS.Capsd");
                        }
                }
        }                
        
        
	/**
	 * Process the event,  add the specified interface into database. If the associated 
         * node does not exist in the database yet, add a node into the database.
	 *
	 * @param event	The event to process.
	 */
	private void addInterfaceHandler(Event event)
	{
                String sourceUei = event.getUei();
                String ipaddr = event.getInterface();

		Category log = ThreadCategory.getInstance(getClass());
		if (log.isDebugEnabled())
			log.debug("addInterfaceHandler:  processing addInterface event for " + ipaddr);

		// Extract node label and transaction No. from the event parms
		String nodeLabel = null;
                long txNo = -1L;
		Parms parms = event.getParms();
		if (parms != null)
		{
			String parmName = null;
			Value parmValue = null;
			String parmContent = null;
		
			Enumeration parmEnum = parms.enumerateParm();
			while(parmEnum.hasMoreElements())
			{
				Parm parm = (Parm)parmEnum.nextElement();
				parmName  = parm.getParmName();
				parmValue = parm.getValue();
				if (parmValue == null)
					continue;
				else 
					parmContent = parmValue.getContent();
	
				//  get node label
				if (parmName.equals(EventConstants.PARM_NODE_LABEL))
				{
					nodeLabel = parmContent;
		                        if (log.isDebugEnabled())
			                        log.debug("addInterfaceHandler:  parmName: " + parmName
                                                        + " /parmContent: " + parmContent);
				}
				else if (parmName.equals(EventConstants.PARM_TRANSACTION_NO))
                                {
                                        String temp = parmContent;
		                        if (log.isDebugEnabled())
			                        log.debug("addInterfaceHandler:  parmName: " + parmName
                                                        + " /parmContent: " + parmContent);
                                        try
                                        {
                                                txNo = Long.valueOf(temp).longValue();
                                        }
                                        catch (NumberFormatException nfe)
                                        {
                                                log.warn("addInterfaceHandler: Parameter " + EventConstants.PARM_TRANSACTION_NO 
                                                        + " cannot be non-numberic", nfe);
                                                txNo = -1L;
                                        }
                                }
						
			}
		}

                boolean invalidParameters = ((ipaddr == null) || (nodeLabel == null));
                if (m_xmlrpc)
                        invalidParameters = invalidParameters || (txNo == -1L);

                if (invalidParameters)
                {
		        if (log.isDebugEnabled())
		                log.debug("addInterfaceHandler:  Invalid parameters." );

                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, sourceUei, 
                                        "Invalid parameters.", status, "OpenNMS.Capsd");
                        }
                        
			return;
		}
                
		// First make sure the specified node label and ipaddress do not exist in the database
                // before trying to add them in. 
		java.sql.Connection dbConn = null;
		PreparedStatement stmt = null;
		try
		{
			dbConn = DatabaseConnectionFactory.getInstance().getConnection();
		
			stmt = dbConn.prepareStatement(SQL_QUERY_IPINTERFACE_EXIST);
	
			stmt.setString(1, nodeLabel);
			stmt.setString(2, ipaddr);
	
			ResultSet rs = stmt.executeQuery();
			while(rs.next())
			{
				if (log.isDebugEnabled())
				{
					log.debug("addInterfaceHandler: node " + nodeLabel + " with IPAddress " 
                                        + ipaddr + " already exist in the database.");
				}
                                
                                if (m_xmlrpc)
                                {
                                        int status = EventConstants.XMLRPC_NOTIFY_SUCCESS;
                                        XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, sourceUei, 
                                                "interface already exists on the node.", status, "OpenNMS.Capsd");
                                }
			        return;
			}
                        stmt.close();
                        
                        // There is no ipinterface associated with the specified nodeLabel exist
                        // in the database. Verify if a node with the nodeLabel already exist in 
                        // the database. If not, create a node with the nodeLabel and add it to the 
                        // database, and also add the ipaddress associated with this node to the 
                        // database. If the node with the nodeLabel exists in the node table, just 
                        // add the ip address to the database.
                        stmt = dbConn.prepareStatement(SQL_QUERY_NODE_EXIST);
                        stmt.setString(1, nodeLabel);

                        rs = stmt.executeQuery();

                        while (rs.next())
                        {

		                if (log.isDebugEnabled())
			                log.debug("addInterfaceHandler:  add interface: " + ipaddr
                                                + " to the database.");
                                                
                                // Node already exists. Add the ipaddess to the ipinterface table
                                InetAddress ifaddr = InetAddress.getByName(ipaddr);
                                int nodeId = rs.getInt(1);
                                String dpName = rs.getString(2);
                                
                                DbIpInterfaceEntry ipInterface = DbIpInterfaceEntry.create(nodeId, ifaddr); 
                                ipInterface.setHostname(ifaddr.getHostName());
                                ipInterface.setManagedState(DbIpInterfaceEntry.STATE_MANAGED);
                                ipInterface.setPrimaryState(DbIpInterfaceEntry.SNMP_NOT_ELIGIBLE);
                                ipInterface.store(dbConn);

                                // create a nodeEntry 
                                DbNodeEntry nodeEntry = DbNodeEntry.get(nodeId, dpName);
                                createAndSendNodeGainedInterfaceEvent(nodeEntry, ifaddr, txNo, sourceUei);
                                if (m_xmlrpc)
                                {
                                        String message = new String("Successfully added interface: ") + ipaddr 
                                                        + " to node: " + nodeLabel;
                                        int status = EventConstants.XMLRPC_NOTIFY_SUCCESS;
                                        XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, sourceUei, message, 
                                                status, "OpenNMS.Capsd"); 
                                }
                                return;
                        }
                        
                        // The node does not exist in the database, add the node and
                        // the ipinterface into the database.
                        addNode(dbConn, nodeLabel, ipaddr, txNo, sourceUei);
		}
		catch(SQLException sqlE)
		{
			log.error("addInterfaceHandler: SQLException during add node and ipaddress to the database.", sqlE);
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, sourceUei, 
                                        sqlE.getMessage(), status, "OpenNMS.Capsd"); 
                        }
		}
		catch(java.net.UnknownHostException e)
		{
			log.error("addInterfaceHandler: can not solve unknow host.", e);
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, sourceUei, 
                                        e.getMessage(), status, "OpenNMS.Capsd"); 
                        }
		}
		finally
		{
			// close the statement
			if (stmt != null)
				try { stmt.close(); } catch(SQLException sqlE) { };

			// close the connection
			if (dbConn != null)
				try { dbConn.close(); } catch(SQLException sqlE) { };					
		}
	}


	/** 
	 * This method is responsible for:
         * 1. stop all services associated with the specified interface.
         * 2. removing all services associated with the interface.
         * 3. remove the interface from the database.
         * 4. issue an interfaceDeleted event to stop polling all the services on 
         *    this interface
         */
	private void deleteInterfaceHandler(Event event)
	{
                String ipaddr = event.getInterface();
                String sourceUei = event.getUei();

                Category log = ThreadCategory.getInstance(getClass());
		if (log.isDebugEnabled())
			log.debug("deleteInterfaceHandler: deleting interface: " + ipaddr);
			
		// Extract node label and transaction No. from the event parms
		String nodeLabel = null;
                long txNo = -1L;
                
		Parms parms = event.getParms();
		if (parms != null)
		{
			String parmName = null;
			Value parmValue = null;
			String parmContent = null;
		
			Enumeration parmEnum = parms.enumerateParm();
			while(parmEnum.hasMoreElements())
			{
				Parm parm = (Parm)parmEnum.nextElement();
				parmName  = parm.getParmName();
				parmValue = parm.getValue();
				if (parmValue == null)
					continue;
				else 
					parmContent = parmValue.getContent();
	
				//  get node label
				if (parmName.equals(EventConstants.PARM_NODE_LABEL))
				{
					nodeLabel = parmContent;
		                        if (log.isDebugEnabled())
			                        log.debug("deleteInterfaceHandler:  parmName: " + parmName
                                                        + " /parmContent: " + parmContent);
				}
				else if (parmName.equals(EventConstants.PARM_TRANSACTION_NO))
                                {
                                        String temp = parmContent;
		                        if (log.isDebugEnabled())
			                        log.debug("deleteInterfaceHandler:  parmName: " + parmName
                                                        + " /parmContent: " + parmContent);
                                        try
                                        {
                                                txNo = Long.valueOf(temp).longValue();
                                        }
                                        catch (NumberFormatException nfe)
                                        {
                                                log.warn("deleteInterfaceHandler: Parameter " + EventConstants.PARM_TRANSACTION_NO 
                                                        + " cannot be non-numberic", nfe);
                                                txNo = -1L;
                                        }
                                }
			}
		}
                
                boolean invalidParameters = ((ipaddr == null) || (nodeLabel == null));
                if (m_xmlrpc)
                        invalidParameters = invalidParameters || (txNo == -1L);
                
                if (invalidParameters)
                {
		        if (log.isDebugEnabled())
		                log.debug("deleteInterfaceHandler:  Invalid parameters." );
                        
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, sourceUei, 
                                        "Invalid parameters.", status, "OpenNMS.Capsd"); 
                        }
                        
			return;
		}

		java.sql.Connection dbConn = null;
		PreparedStatement stmt = null;
		try
		{
			dbConn = DatabaseConnectionFactory.getInstance().getConnection();
		        
                        // First, verify if the node exists in database, and retrieve
                        // nodeid if exists.
			stmt = dbConn.prepareStatement(SQL_QUERY_NODE_EXIST);
	
			stmt.setString(1, nodeLabel);
                        int nodeid = -1;
                        
			ResultSet rs = stmt.executeQuery();
			while(rs.next())
			{
                                nodeid = rs.getInt(1);
                        }
		        
                        if (nodeid == -1)  // Sanity check
		        {
			        log.error("deleteInterfaceHandler: There is no node with node label: " 
                                        + nodeLabel + " exists in the database.");
                        
                                if (m_xmlrpc)
                                {
                                        int status = EventConstants.XMLRPC_NOTIFY_SUCCESS;
                                        XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, sourceUei, 
                                                "No node with the specified node label exists.", status, "OpenNMS.Capsd");
                                }
			        return;
		        }
	                rs.close();	
                        stmt.close();
                        
                        // Count interfaces on the node
			if (log.isDebugEnabled())
		        {
			        log.debug("deleteInterfaceHandler: count interfaces on node: " + nodeLabel);
		        }
		        stmt = dbConn.prepareStatement(SQL_COUNT_INTERFACES_ON_NODE);
		        stmt.setString(1, nodeLabel);
		        rs = stmt.executeQuery();
                        int numOfInterface = 0;
                                       
                        if (rs.next())
                              numOfInterface = rs.getInt(1);

                        // if the interface is the only interface on the node, issue a delete node event.
                        if (numOfInterface == 1 )
                        {
                                createAndSendDeleteNodeEvent(event.getHost(), nodeLabel, txNo, sourceUei);
                        }
                        else
                        {
                                // Deleting all usersnotified info associated with the nodeid and ipaddress
        			stmt = dbConn.prepareStatement(SQL_DELETE_USERSNOTIFIED_ON_INTERFACE);
        			stmt.setInt(1, nodeid);
                                stmt.setString(2, ipaddr);
        			stmt.executeUpdate();
        			if (log.isDebugEnabled())
                                {
        				log.debug("deleteInterfaceHandler: deleted all usersnotified info on interface: "
                                                + ipaddr + " at nodeid: " + nodeid);
                                }
                                stmt.close();
        	                
                                // Deleting all the notifications associated with the nodeid and ipaddress
        			stmt = dbConn.prepareStatement(SQL_DELETE_NOTIFICATIONS_ON_INTERFACE);
        			stmt.setInt(1, nodeid);
                                stmt.setString(2, ipaddr);
        			stmt.executeUpdate();
        			if (log.isDebugEnabled())
                                {
        				log.debug("deleteInterfaceHandler: deleted all notifications on interface: "
                                                + ipaddr + " at nodeid: " + nodeid);
                                }
                                stmt.close();
                                
                                // Deleting all outages associated with the nodeid and ipaddress
        			stmt = dbConn.prepareStatement(SQL_DELETE_OUTAGES_ON_INTERFACE);
        			stmt.setInt(1, nodeid);
                                stmt.setString(2, ipaddr);
        			stmt.executeUpdate();
        			if (log.isDebugEnabled())
                                {
        				log.debug("deleteInterfaceHandler: deleted all outages on interface: "
                                                + ipaddr + " at nodeid: " + nodeid);
                                }
                                stmt.close();
                                
                                // Deleting all events associated with the nodeid and ipaddress
        			stmt = dbConn.prepareStatement(SQL_DELETE_EVENTS_ON_INTERFACE);
        			stmt.setInt(1, nodeid);
                                stmt.setString(2, ipaddr);
        			stmt.executeUpdate();
        			if (log.isDebugEnabled())
                                {
        				log.debug("deleteInterfaceHandler: deleted all events on interface: "
                                                + ipaddr + " at nodeid: " + nodeid);
                                }
                                stmt.close();
                                
                                // Deleting the snmpinterface entry with the nodeid and ipaddress
        			stmt = dbConn.prepareStatement(SQL_DELETE_SNMPINTERFACE_ON_INTERFACE);
        			stmt.setInt(1, nodeid);
                                stmt.setString(2, ipaddr);
        			stmt.executeUpdate();
        			if (log.isDebugEnabled())
                                {
        				log.debug("deleteInterfaceHandler: deleted the snmpinterface entry of interface: "
                                                + ipaddr + " at nodeid: " + nodeid);
                                }
                                stmt.close();
                                
                                // Deleting all the ifservices associated with the nodeid and ipaddress
        			stmt = dbConn.prepareStatement(SQL_DELETE_IFSERVICES_ON_INTERFACE);
        			stmt.setInt(1, nodeid);
                                stmt.setString(2, ipaddr);
        			stmt.executeUpdate();
        			if (log.isDebugEnabled())
                                {
        				log.debug("deleteInterfaceHandler: deleted all ifservices on interface: "
                                                + ipaddr + " at nodeid: " + nodeid);
                                }
                                stmt.close();
        
                                // if the deleted interface is the SNMP primary interface of a node,
                                // an forceRescan event should be issued.
                                //
                                stmt = dbConn.prepareStatement(SQL_QUERY_PRIMARY_INTERFACE);
                                stmt.setInt(1, nodeid);
                                stmt.setString(2, ipaddr);
                                
                                rs = stmt.executeQuery();
                                char isPrimary = 'N';
                                if (rs.next())
                                        isPrimary = rs.getString(1).charAt(0);

                                rs.close();
                                stmt.close();
        	                // Deleting the interface on the node
        			stmt = dbConn.prepareStatement(SQL_DELETE_INTERFACE);
        			stmt.setInt(1, nodeid);
                                stmt.setString(2, ipaddr);
        			stmt.executeUpdate();
        			
                                stmt.close();
                                
                                if (log.isDebugEnabled())
                                {
        				log.debug("deleteInterfaceHandler: deleted the ipaddress: " 
                                                + ipaddr + " on  nodeid: " + nodeid);
                                
        			}
                                        
                                // Create an interfaceDeleted event and send it to eventd, this new event will 
                                // remove all the services on the interface and the interface from the 
                                // nodeid/interface/services conbination of the pollable node list.
                                //
                                createAndSendInterfaceDeletedEvent(nodeid, event.getHost(), ipaddr, txNo, sourceUei);
                                if (m_xmlrpc)
                                {
                                        int status = EventConstants.XMLRPC_NOTIFY_SUCCESS;
                                        String message = new String("Successfully deleted interface: ") + ipaddr 
                                                        + " on node: " + nodeLabel;
                                        XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, sourceUei, message, 
                                                status, "OpenNMS.Capsd"); 
                                }
                                
                                if (isPrimary == 'P')
                                        createAndSendForceRescanEvent(event.getHost(), (long)nodeid);
                        }
	                
		}
		catch(SQLException sqlE)
		{
			log.error("deleteInterfaceHandler: SQLException during delete interface from the database.", sqlE);
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, sourceUei, 
                                        sqlE.getMessage(), status, "OpenNMS.Capsd"); 
                        }
		}
		finally
		{
			// close the statement
			if (stmt != null)
				try { stmt.close(); } catch(SQLException sqlE) { };

			// close the connection
			if (dbConn != null)
				try { dbConn.close(); } catch(SQLException sqlE) { };					
		}
	}
      
      
        /**
         * This method is responsible for generating a deleteNode event and sending
         * it to eventd..
         *
         * @param hostName      the Host server name. 
         * @param nodeLabel     the nodelabel of the deleted node.
         * @param txNo          the external transaction No of the event.
         * @param callerUei     the uei of the caller event.
         */
        private void createAndSendDeleteNodeEvent(String hostName, String nodeLabel, long txNo, String callerUei)
        {
		Category log = ThreadCategory.getInstance(getClass());
		if (log.isDebugEnabled())
                        log.debug("createdAndSendDeleteNodeEvent: processing deleteInterface event... "); 
        
                Event newEvent = new Event();
                newEvent.setUei(EventConstants.DELETE_NODE_EVENT_UEI);
                newEvent.setSource("OpenNMS.Capsd");
                newEvent.setHost(hostName);
                newEvent.setTime(EventConstants.formatToString(new java.util.Date()));

                // Add appropriate parms
                Parms eventParms = new Parms();
                Parm eventParm = null;
                Value parmValue = null;

                eventParm = new Parm();
                eventParm.setParmName(EventConstants.PARM_NODE_LABEL);
                parmValue = new Value();
                parmValue.setContent(nodeLabel);
                eventParm.setValue(parmValue);
                eventParms.addParm(eventParm);

                eventParm = new Parm();
                eventParm.setParmName(EventConstants.PARM_TRANSACTION_NO);
                parmValue = new Value();
                parmValue.setContent((new Long(txNo)).toString());
                eventParm.setValue(parmValue);
                eventParms.addParm(eventParm);

                // Add Parms to the event
                newEvent.setParms(eventParms);

                // Send event to Eventd
                try
                {
                        EventIpcManagerFactory.getInstance().getManager().sendNow(newEvent);

                        if (log.isDebugEnabled())
                                log.debug("createAndSendDeleteNodeEvent: successfully sent deleteNode event for node: " 
                                        + nodeLabel);
                }
                catch(Throwable t)
                {
                        log.warn("run: unexpected throwable exception caught during send to middleware", t);
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, callerUei, 
                                           "caught unexpected throwable exception.", status, "OpenNMS.Capsd");
                        }
                }
        }                
        
        /**
         * This method is responsible for generating a forceRescan event and sending
         * it to eventd..
         *
         * @param hostName      the Host server name. 
         * @param nodeId        the node ID of the node to rescan.
         */
        private void createAndSendForceRescanEvent(String hostName, long nodeId)
        {
		Category log = ThreadCategory.getInstance(getClass());
		if (log.isDebugEnabled())
                        log.debug("createdAndSendForceRescanEvent: processing forceRescan event... "); 
        
                Event newEvent = new Event();
                newEvent.setUei(EventConstants.FORCE_RESCAN_EVENT_UEI);
                newEvent.setSource("OpenNMS.Capsd");
                newEvent.setNodeid(nodeId);
                newEvent.setHost(hostName);
                newEvent.setTime(EventConstants.formatToString(new java.util.Date()));

                // Send event to Eventd
                try
                {
                        EventIpcManagerFactory.getInstance().getManager().sendNow(newEvent);

                        if (log.isDebugEnabled())
                                log.debug("createAndSendForceRescanEvent: successfully sent forceRescan event for node: " 
                                        + nodeId);
                }
                catch(Throwable t)
                {
                        log.warn("run: unexpected throwable exception caught during send to middleware", t);
                }
        }                
       
        /**
         * This method is responsible for generating an interfaceDeleted event and sending
         * it to eventd...
         *
         * @param nodeId        Nodeid of the node that the deleted interface resides on.
         * @param hostName      the Host server name. 
         * @param ipaddr        the ipaddress of the deleted Interface. 
         * @param txNo          the external transaction No. of the original event.
         * @param callerUei     the uei of the caller event
         */
        private void createAndSendInterfaceDeletedEvent(int nodeId, String hostName, String ipaddr, 
                                                        long txNo, String callerUei)
        {
		Category log = ThreadCategory.getInstance(getClass());
		if (log.isDebugEnabled())
			log.debug("createAndSendInterfaceDeletedEvent:  processing deleteInterface event for interface: " 
                                + ipaddr + " at nodeid: " + nodeId);
        
                Event newEvent = new Event();
                newEvent.setUei(EventConstants.INTERFACE_DELETED_EVENT_UEI);
                newEvent.setSource("OpenNMS.capsd");
                newEvent.setNodeid(nodeId);
                newEvent.setInterface(ipaddr);
                newEvent.setHost(hostName);
                newEvent.setTime(EventConstants.formatToString(new java.util.Date()));
                
                // Add appropriate parms
                Parms eventParms = new Parms();
                Parm eventParm = null;
                Value parmValue = null;

                eventParm = new Parm();
                eventParm.setParmName(EventConstants.PARM_TRANSACTION_NO);
                parmValue = new Value();
                parmValue.setContent((new Long(txNo)).toString());
                eventParm.setValue(parmValue);
                eventParms.addParm(eventParm);
                
                // Add Parms to the event
                newEvent.setParms(eventParms);

                // Send event to Eventd
                try
                {
                        EventIpcManagerFactory.getInstance().getManager().sendNow(newEvent);

                        if (log.isDebugEnabled())
                                log.debug("createdAndSendInterfaceDeletedEvent: successfully sent interfaceDeleted event for nodeid: " 
                                        + nodeId);
                }
                catch(Throwable t)
                {
                        log.warn("run: unexpected throwable exception caught during send to middleware", t);
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, callerUei, 
                                           "caught unexpected throwable exception.", status, "OpenNMS.Capsd");
                        }
                }
        }                

        
	/**
	 * Process the event,  add or remove a specified service from an interface. An 'action'
         * parameter wraped in the event will tell which action to take to the service.
	 *
	 * @param event	The event to process.
	 */
	private void changeServiceHandler(Event event)
	{
	        String ipaddr = event.getInterface();
                String serviceName = event.getService();
                String sourceUei = event.getUei();
                
                Category log = ThreadCategory.getInstance(getClass());
		if (log.isDebugEnabled())
			log.debug("changeServiceHandler:  processing changeService event on: " + ipaddr);

		// Extract action from the event parms
		String action = null;
                long txNo = -1;
		Parms parms = event.getParms();
		if (parms != null)
		{
                                
			String parmName = null;
			Value parmValue = null;
			String parmContent = null;
		
			Enumeration parmEnum = parms.enumerateParm();
			while(parmEnum.hasMoreElements())
			{
				Parm parm = (Parm)parmEnum.nextElement();
				parmName  = parm.getParmName();
				parmValue = parm.getValue();
				if (parmValue == null)
					continue;
                                else 
					parmContent = parmValue.getContent();
				//  get the action 
				if (parmName.equals(EventConstants.PARM_ACTION))
				{
					action = parmContent;
		                        if (log.isDebugEnabled())
			                        log.debug("changeServiceHandler:  ParmName:" + parmName 
                                                + " / ParmContent: " + parmContent);
				}
				else if (parmName.equals(EventConstants.PARM_TRANSACTION_NO))
                                {
                                        String temp = parmContent;
		                        if (log.isDebugEnabled())
			                        log.debug("changeServiceHandler:  parmName: " + parmName
                                                        + " /parmContent: " + parmContent);
                                        try
                                        {
                                                txNo = Long.valueOf(temp).longValue();
                                        }
                                        catch (NumberFormatException nfe)
                                        {
                                                log.warn("changeServiceHandler: Parameter " + EventConstants.PARM_TRANSACTION_NO 
                                                        + " cannot be non-numberic", nfe);
                                                txNo = -1L;
                                        }
                                }
						
			}
		}
                
                boolean invalidParameters = ((ipaddr == null) || (action == null) || serviceName == null);
                if(m_xmlrpc)
                        invalidParameters = invalidParameters || (txNo == -1L);

                if (invalidParameters)
                {
		        if (log.isDebugEnabled())
		                log.debug("changeServiceHandler:  Invalid parameters." );
                        
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, sourceUei, 
                                           "Invalid parameters.", status, "OpenNMS.Capsd");
                        }
                        
			return;
		}

		java.sql.Connection dbConn = null;
		PreparedStatement stmt = null;
		try
		{
			dbConn = DatabaseConnectionFactory.getInstance().getConnection();

                        // Retrieve the serviceId
			stmt = dbConn.prepareStatement(SQL_RETRIEVE_SERVICE_ID);
	
			stmt.setString(1, serviceName);
	
			ResultSet rs = stmt.executeQuery();
                        int  serviceId = -1;
			while(rs.next())
			{
				if (log.isDebugEnabled())
					log.debug("changeServiceHandler: retrieve serviceid for service " + serviceName);
                                serviceId = rs.getInt(1); 
			}
                        
                        if (serviceId < 0)
                        {
				if (log.isDebugEnabled())
					log.debug("changeServiceHandler: the specified service: " 
                                                + serviceName + " does not exist in the database.");
                                if (m_xmlrpc)
                                {
                                        StringBuffer message = new StringBuffer("Invalid service: ").append(serviceName);
                                        int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                        XmlrpcUtil.createAndSendXmlrpcNotificationEvent( txNo, sourceUei, 
                                                   message.toString(), status, "OpenNMS.Capsd");
                                }
                                return;
                        }
                        
                        stmt.close();

                        int nodeId = -1;
                        
                        // Verify if the specified service already exist.        
			stmt = dbConn.prepareStatement(SQL_QUERY_SERVICE_EXIST);
	
			stmt.setString(1, ipaddr);
			stmt.setString(2, event.getService());
	
			rs = stmt.executeQuery();
			while(rs.next())
			{
				if (log.isDebugEnabled())
				{
					log.debug("changeService: service " + serviceName  + " on IPAddress " 
                                        + ipaddr + " already exists in the database.");
				}
                                nodeId = rs.getInt(1);
                                
                                // The service exists on the ipinterface, a 'DELETE operation could be performed,
                                // but just return for the 'ADD' operation.
                                if (action.equalsIgnoreCase("DELETE"))
                                {
                                        // Create a deleteService event to eventd
                                        DbNodeEntry nodeEntry = DbNodeEntry.get(nodeId);
                                        createAndSendDeleteServiceEvent(nodeEntry, InetAddress.getByName(ipaddr), 
                                                                         serviceName, txNo, sourceUei);
                                        
                                        if (m_xmlrpc)
                                        {
                                                StringBuffer message = new StringBuffer("Deleted service: ");
                                                message.append(serviceName).append(" on ").append(ipaddr);
                                                int status = EventConstants.XMLRPC_NOTIFY_SUCCESS;
                                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent( txNo, sourceUei, 
                                                           message.toString(), status, "OpenNMS.Capsd");
                                        }
                                }
			        else // could not perform 'ADD' since the service already exists.
                                {
                                        log.warn("changeServiceHandler: could not add an existing service in.");
                                        if (m_xmlrpc)
                                        {
                                                StringBuffer message = new StringBuffer("Could not add in service: ");
                                                message.append(serviceName).append(". It is already in the database.");
                                                int status = EventConstants.XMLRPC_NOTIFY_SUCCESS;
                                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent( txNo, sourceUei,
                                                           message.toString(), status, "OpenNMS.Capsd");
                                        }
                                }
                                
                                return;
			}
                        stmt.close();
                        
                        // verify if the interface exists in the database. If it does, that means
                        // the service does not exist in the database, and an 'ADD' operation could
                        // be performed. In any other cases, log error message.
                        if (action.equalsIgnoreCase("ADD"))
                        {
                                stmt = dbConn.prepareStatement(SQL_QUERY_IPADDRESS_EXIST);

                                stmt.setString(1, ipaddr);
                                rs = stmt.executeQuery();
                                
                                while (rs.next())
                                {
				        if (log.isDebugEnabled())
				        {
					        log.debug("changeServiceHandler: add service " + serviceName  
                                                + " to interface: " + ipaddr);
				        }

                                        nodeId = rs.getInt(1);
                                        // insert service
                                        DbIfServiceEntry service = DbIfServiceEntry.create(nodeId, 
                                                InetAddress.getByName(ipaddr),
                                                serviceId);
                                        service.setSource(DbIfServiceEntry.SOURCE_PLUGIN);
                                        service.setStatus(DbIfServiceEntry.STATUS_ACTIVE);
                                        service.setNotify(DbIfServiceEntry.NOTIFY_ON);
                                        service.store(dbConn);
                                        
                                        //Create a nodeGainedService event to eventd. 
                                        DbNodeEntry nodeEntry = DbNodeEntry.get(nodeId);
                                        createAndSendNodeGainedServiceEvent(nodeEntry,
                                                                            InetAddress.getByName(ipaddr),
                                                                            serviceName,
                                                                            txNo,
                                                                            sourceUei);
                                        if (m_xmlrpc)
                                        {
                                                StringBuffer message = new StringBuffer("Added service: ");
                                                message.append(serviceName).append(" to ").append(ipaddr);
                                                message.append(" on node: ").append(nodeEntry.getLabel());
                                                int status = EventConstants.XMLRPC_NOTIFY_SUCCESS;
                                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent( txNo, sourceUei,
                                                           message.toString(), status, "OpenNMS.Capsd");
                                        }
                                }
                        }
                        else 
                        {
                                log.error("changeServiceHandler: could not delete non-existing service.");
                                if (m_xmlrpc)
                                {
                                        StringBuffer message = new StringBuffer("Could not delete non-existing service: ");
                                        message.append(serviceName).append(".");
                                        int status = EventConstants.XMLRPC_NOTIFY_SUCCESS;
                                        XmlrpcUtil.createAndSendXmlrpcNotificationEvent( txNo, sourceUei,
                                                   message.toString(), status, "OpenNMS.Capsd");
                                }
                        }
		}
		catch(SQLException sqlE)
		{
			log.error("SQLException during changeService on database.", sqlE);
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, sourceUei, 
                                           sqlE.getMessage(), status, "OpenNMS.Capsd");
                        }
		}
		catch(java.net.UnknownHostException e)
		{
			log.error("changeServiceHandler: can not solve unknow host.", e);
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, sourceUei, 
                                           e.getMessage(), status, "OpenNMS.Capsd");
                        }
		}
		finally
		{
			// close the statement
			if (stmt != null)
				try { stmt.close(); } catch(SQLException sqlE) { };

			// close the connection
			if (dbConn != null)
				try { dbConn.close(); } catch(SQLException sqlE) { };					
		}
		
	}

        /**
         * This method is responsible for generating a nodeGainedService event and sending
         * it to eventd..
         *
         * @param nodeEntry     The node that gained the service.
         * @param ifaddr        the interface gained the service.
         * @param service       the service gained.
         * @param txNo          the transaction no.
         * @param callerUei     the uei of the caller event.
         *
         */
        private void createAndSendNodeGainedServiceEvent(DbNodeEntry nodeEntry, InetAddress ifaddr, 
                                                         String service, long txNo, String callerUei)
        {
		Category log = ThreadCategory.getInstance(getClass());
		if (log.isDebugEnabled())
			log.debug("createAndSendNodeGainedServiceEvent:  nodeId/interface/service  " 
                        + nodeEntry.getNodeId() + "/" + ifaddr.getHostAddress() + "/" + service);
        
                Event newEvent = new Event();
                newEvent.setUei(EventConstants.NODE_GAINED_SERVICE_EVENT_UEI);
                newEvent.setSource("OpenNMS.Capsd");
                newEvent.setNodeid(nodeEntry.getNodeId());
                newEvent.setHost(Capsd.getLocalHostAddress());
                newEvent.setInterface(ifaddr.getHostAddress());
                newEvent.setService(service);
                newEvent.setTime(EventConstants.formatToString(new java.util.Date()));

                // Add appropriate parms
                Parms eventParms = new Parms();
                Parm eventParm = null;
                Value parmValue = null;

                // Add IP host name
                eventParm = new Parm();
                eventParm.setParmName(EventConstants.PARM_IP_HOSTNAME);
                parmValue = new Value();
                parmValue.setContent(ifaddr.getHostName());
                eventParm.setValue(parmValue);
                eventParms.addParm(eventParm);
                
                // Add Node Label
                eventParm = new Parm();
                eventParm.setParmName(EventConstants.PARM_NODE_LABEL);
                parmValue = new Value();
                parmValue.setContent(nodeEntry.getLabel());
                eventParm.setValue(parmValue);
                eventParms.addParm(eventParm);

                // Add Node Label source
                eventParm = new Parm();
                eventParm.setParmName(EventConstants.PARM_NODE_LABEL_SOURCE);
                parmValue = new Value();
                char labelSource[] = new char[] {nodeEntry.getLabelSource()};
                parmValue.setContent(new String(labelSource));
                eventParm.setValue(parmValue);
                eventParms.addParm(eventParm);

                // Add sysName if available
                if (nodeEntry.getSystemName() != null)
                {
                        eventParm = new Parm();
                        eventParm.setParmName(EventConstants.PARM_NODE_SYSNAME);
                        parmValue = new Value();
                        parmValue.setContent(nodeEntry.getSystemName());
                        eventParm.setValue(parmValue);
                        eventParms.addParm(eventParm);
                }

                // Add sysDescr if available
                if (nodeEntry.getSystemDescription() != null)
                {
                        eventParm = new Parm();
                        eventParm.setParmName(EventConstants.PARM_NODE_SYSDESCRIPTION);
                        parmValue = new Value();
                        parmValue.setContent(nodeEntry.getSystemDescription());
                        eventParm.setValue(parmValue);
                        eventParms.addParm(eventParm);
                }
                
                // Add Parms to the event
                newEvent.setParms(eventParms);
                
                // Send event to Eventd
                try
                {
                        EventIpcManagerFactory.getInstance().getManager().sendNow(newEvent);

                        if (log.isDebugEnabled())
                                log.debug("createdAndSendNodeGainedServiceEvent: successfully sent nodeGainedService event "
                                        + "for nodeid/interface/service: " 
                                        + nodeEntry.getNodeId() + "/"
                                        + ifaddr.getHostAddress() + "/" + service);
                                        
                }
                catch(Throwable t)
                {
                        log.warn("run: unexpected throwable exception caught during send to middleware", t);
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, callerUei, 
                                           "caught unexpected throwable exception.", status, "OpenNMS.Capsd");
                        }
                }
        }                
	
        /**
         * This method is responsible for generating a deleteService event and sending
         * it to eventd..
         *
         * @param nodeEntry     The node that the service to get deleted on.
         * @param ifaddr        the interface the service to get deleted on.
         * @param service       the service to delete.
         * @param txNo          the transaction no.
         * @param callerUei     the uei of the caller event.
         */
        private void createAndSendDeleteServiceEvent( DbNodeEntry nodeEntry, InetAddress ifaddr, 
                                                       String service, long txNo, String callerUei)
        {
		Category log = ThreadCategory.getInstance(getClass());
		if (log.isDebugEnabled())
			log.debug("createAndSendDeleteServiceEvent:  nodeId/interface/service  " 
                        + nodeEntry.getNodeId() + "/" + ifaddr.getHostAddress() + "/" + service);
        
                Event newEvent = new Event();
                newEvent.setUei(EventConstants.DELETE_SERVICE_EVENT_UEI);
                newEvent.setSource("OpenNMS.Capsd");
                newEvent.setNodeid(nodeEntry.getNodeId());
                newEvent.setHost(Capsd.getLocalHostAddress());
                newEvent.setInterface(ifaddr.getHostAddress());
                newEvent.setService(service);
                newEvent.setTime(EventConstants.formatToString(new java.util.Date()));

                // Add appropriate parms
                Parms eventParms = new Parms();
                Parm eventParm = null;
                Value parmValue = null;

                // Add IP host name
                eventParm = new Parm();
                eventParm.setParmName(EventConstants.PARM_IP_HOSTNAME);
                parmValue = new Value();
                parmValue.setContent(ifaddr.getHostName());
                eventParm.setValue(parmValue);
                eventParms.addParm(eventParm);
                
                // Add Node Label
                eventParm = new Parm();
                eventParm.setParmName(EventConstants.PARM_NODE_LABEL);
                parmValue = new Value();
                parmValue.setContent(nodeEntry.getLabel());
                eventParm.setValue(parmValue);
                eventParms.addParm(eventParm);

                // Add Node Label source
                eventParm = new Parm();
                eventParm.setParmName(EventConstants.PARM_NODE_LABEL_SOURCE);
                parmValue = new Value();
                char labelSource[] = new char[] {nodeEntry.getLabelSource()};
                parmValue.setContent(new String(labelSource));
                eventParm.setValue(parmValue);
                eventParms.addParm(eventParm);

                // Add Parms to the event
                newEvent.setParms(eventParms);
                
                // Send event to Eventd
                try
                {
                        EventIpcManagerFactory.getInstance().getManager().sendNow(newEvent);

                        if (log.isDebugEnabled())
                                log.debug("createdAndSendDeleteServiceEvent: successfully sent serviceDeleted event "
                                        + "for nodeid/interface/service: " 
                                        + nodeEntry.getNodeId() + "/"
                                        + ifaddr.getHostAddress() + "/" + service);
                                        
                }
                catch(Throwable t)
                {
                        log.warn("run: unexpected throwable exception caught during send to middleware", t);
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, callerUei, 
                                           "caught unexpected throwable exception.", status, "OpenNMS.Capsd");
                        }
                }
        }                

	/**
	 * Process the event, add or remove a specified interface from an opennms server. An 'action'
         * parameter wraped in the event will tell which action to take to the interface, and a 'nodelabel'
         * parameter wraped in the event will tell the node that the interface resides on. The interface 
         * ipaddress and the opennms server hostname is included in the event.
	 *
	 * @param event	The event to process.
	 */
	private void updateServerHandler(Event event)
	{
	        String ipaddr = event.getInterface();
                String hostName = getLocalServer();
                String sourceUei = event.getUei();

                Category log = ThreadCategory.getInstance(getClass());
		if (log.isDebugEnabled())
			log.debug("updateServerHandler:  processing updateServer event for: " 
                                + ipaddr + " on OpenNMS server: " + hostName);

                // Extract action, nodeLabel and external transaction number from 
                // the event parms
                //
		String action = null;
                String nodeLabel = null;
                long txNo = -1L;
                
		Parms parms = event.getParms();
		if (parms != null)
		{
                                
			String parmName = null;
			Value parmValue = null;
			String parmContent = null;
		
			Enumeration parmEnum = parms.enumerateParm();
			while(parmEnum.hasMoreElements())
			{
				Parm parm = (Parm)parmEnum.nextElement();
				parmName  = parm.getParmName();
				parmValue = parm.getValue();
				if (parmValue == null)
					continue;
                                else 
					parmContent = parmValue.getContent();
				//  get the action and nodelabel
				if (parmName.equals(EventConstants.PARM_ACTION))
				{
					action = parmContent;
		                        if (log.isDebugEnabled())
			                        log.debug("updateServerHandler:  ParmName:" + parmName 
                                                + " / ParmContent: " + parmContent);
				} 
                                else	if (parmName.equals(EventConstants.PARM_NODE_LABEL))
				{
					nodeLabel = parmContent;
		                        if (log.isDebugEnabled())
			                        log.debug("updateServerHandler:  ParmName:" + parmName 
                                                + " / ParmContent: " + parmContent);
				}
				else if (parmName.equals(EventConstants.PARM_TRANSACTION_NO))
                                {
                                        String temp = parmContent;
		                        if (log.isDebugEnabled())
			                        log.debug("updateServerHandler:  parmName: " + parmName
                                                        + " /parmContent: " + parmContent);
                                        try
                                        {
                                                txNo = Long.valueOf(temp).longValue();
                                        }
                                        catch (NumberFormatException nfe)
                                        {
                                                log.warn("updateServerHandler: Parameter " + EventConstants.PARM_TRANSACTION_NO 
                                                        + " cannot be non-numberic", nfe);
                                                txNo = -1L;
                                        }
                                }
						
			}
		}
                
                // Notify the external xmlrpc server receiving of the event.
                //
                if (m_xmlrpc)
                {
                        StringBuffer message = new StringBuffer("Received event: UpdateServer-- action: ").append(action);
                        message.append(". nodeLabel/ipaddr/hostname: ").append(nodeLabel).append("/");
                        message.append(ipaddr).append("/").append(hostName);
                        int status = EventConstants.XMLRPC_NOTIFY_RECEIVED;
                        XmlrpcUtil.createAndSendXmlrpcNotificationEvent(txNo, sourceUei,message.toString(), 
                                status, "OpenNMS.Capsd"); 
                }
		boolean invalidParameters = ((ipaddr == null) || (hostName == null) || (nodeLabel == null) || (action == null));
                if (m_xmlrpc)
                        invalidParameters = invalidParameters || (txNo == -1L);
                
                if (invalidParameters)
                {
		        if (log.isDebugEnabled())
		                log.debug("updateServerHandler:  Invalid parameters." );
                        
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent( txNo, sourceUei,
                                           "Invalid parameters.", status, "OpenNMS.Capsd"); 
                        }
                        
			return;
		}

		java.sql.Connection dbConn = null;
		PreparedStatement stmt = null;
		try
		{
			dbConn = DatabaseConnectionFactory.getInstance().getConnection();

                        // Verify if the interface already exists on the NMS server 
			stmt = dbConn.prepareStatement(SQL_QUERY_INTERFACE_ON_SERVER);
	
			stmt.setString(1, ipaddr);
                        stmt.setString(2, hostName);
	
			ResultSet rs = stmt.executeQuery();
	
			rs = stmt.executeQuery();
			while(rs.next())
			{
				if (log.isDebugEnabled())
				{
					log.debug("updateServer: the Interface "  + ipaddr + 
                                                " on NMS server: " + hostName + " already exists in the database.");
				}
                                
                                // The interface exists on the NMS server, a 'DELETE operation could be performed,
                                // but just return for the 'ADD' operation.
                                if (action.equalsIgnoreCase("DELETE"))
                                {
                                        // Delete all services on the specified interface in interface/service mapping
                                        //
				        if (log.isDebugEnabled())
				        {
					        log.debug("updateServer: delete all services on the interface: " + ipaddr
                                                        + " in the interface/service mapping." );
				        }
			                stmt = dbConn.prepareStatement(SQL_DELETE_ALL_SERVICES_INTERFACE_MAPPING);
			                stmt.setString(1, ipaddr);
			                stmt.executeUpdate();

                                        
                                        // Delete the interface on interface/server mapping
				        if (log.isDebugEnabled())
				        {
					        log.debug("updateServer: delete interface: " + ipaddr
                                                        + " on NMS server: " + hostName);
				        }
			                stmt = dbConn.prepareStatement(SQL_DELETE_INTERFACE_ON_SERVER);
			                stmt.setString(1, ipaddr);
			                stmt.setString(2, hostName);
			                stmt.executeUpdate();
                                        
                                        //Create a deleteInterface event to eventd.
                                        createAndSendDeleteInterfaceEvent(nodeLabel, ipaddr, hostName, 
                                                                          txNo, sourceUei);
                                }
			        else // could not perform 'ADD' since the service already exists.
                                {
                                        log.warn("updateServerHandler: the interface " + ipaddr 
                                                + " already exist on NMS server: " + hostName 
                                                + ". Could not perform 'ADD' operation.");
                                        
                                        if (m_xmlrpc)
                                        {
                                                int status = EventConstants.XMLRPC_NOTIFY_SUCCESS;
                                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent( txNo, sourceUei,
                                                           "Interface already exists.", status, "OpenNMS.Capsd"); 
                                        }
                                }
                                
                                return;
			}
                        stmt.close();
                        
                        // the interface does not exist on the NMS server yet, an 'ADD' operation could
                        // be performed. In any other cases, log error message.
                        if (action.equalsIgnoreCase("ADD"))
                        {
                                stmt = dbConn.prepareStatement(SQL_ADD_INTERFACE_TO_SERVER);

                                stmt.setString(1, ipaddr);
                                stmt.setString(2, hostName);
                                stmt.executeUpdate();
                                
			        if (log.isDebugEnabled())
			        {
				        log.debug("updateServerHandler: added interface " + ipaddr  
                                               + " into NMS server: " + hostName);
			        }

                                //Create a addInterface event and process it. 
                                createAndSendAddInterfaceEvent(nodeLabel, ipaddr, hostName, txNo, sourceUei);

                        }
                        else 
                        {
                                log.error("updateServerHandler: could not delete non-existing interface: " + ipaddr
                                + " on NMS server: " + hostName);
                                
                                if (m_xmlrpc)
                                {
                                        int status = EventConstants.XMLRPC_NOTIFY_SUCCESS;
                                        XmlrpcUtil.createAndSendXmlrpcNotificationEvent( txNo, sourceUei,
                                                   "Interface not exist yet.", status, "OpenNMS.Capsd"); 
                                }
                        }
		}
		catch(SQLException sqlE)
		{
			log.error("SQLException during updateServer on database.", sqlE);
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent( txNo, sourceUei,
                                           sqlE.getMessage(), status, "OpenNMS.Capsd"); 
                        }
		}
		finally
		{
			// close the statement
			if (stmt != null)
				try { stmt.close(); } catch(SQLException sqlE) { };

			// close the connection
			if (dbConn != null)
				try { dbConn.close(); } catch(SQLException sqlE) { };					
		}
		
	}

        
        /**
         * This method is responsible for generating an addInterface  event 
         * and sending it to eventd..
         *
         * @param nodeLabel     the node label of the node where the interface resides.
         * @param ipaddr        IP address of the interface to be added.
         * @param hostName      the Host server name. 
         * @param txNo          the exteranl transaction number
         * @param callerUei     the uei of the caller event
         */
        private void createAndSendAddInterfaceEvent(String nodeLabel, String ipaddr, String hostName, 
                                                    long txNo, String callerUei)
        {
		Category log = ThreadCategory.getInstance(getClass());
		if (log.isDebugEnabled())
			log.debug("createAndSendAddInterfaceEvent:  processing updateServer event for interface:  " 
                                + ipaddr + " on server: " + hostName);
        
                Event newEvent = new Event();
                newEvent.setUei(EventConstants.ADD_INTERFACE_EVENT_UEI);
                newEvent.setSource("OpenNMS.capsd");
                newEvent.setInterface(ipaddr);
                newEvent.setHost(hostName);
                newEvent.setTime(EventConstants.formatToString(new java.util.Date()));

                // Add appropriate parms
                Parms eventParms = new Parms();
                Parm eventParm = null;
                Value parmValue = null;

                eventParm = new Parm();
                eventParm.setParmName(EventConstants.PARM_NODE_LABEL);
                parmValue = new Value();
                parmValue.setContent(nodeLabel);
                eventParm.setValue(parmValue);
                eventParms.addParm(eventParm);

                eventParm = new Parm();
                eventParm.setParmName(EventConstants.PARM_TRANSACTION_NO);
                parmValue = new Value();
                parmValue.setContent((new Long(txNo)).toString());
                eventParm.setValue(parmValue);
                eventParms.addParm(eventParm);

                // Add Parms to the event
                newEvent.setParms(eventParms);
                
                // Send event to Eventd
                try
                {
                        EventIpcManagerFactory.getInstance().getManager().sendNow(newEvent);

                        if (log.isDebugEnabled())
                                log.debug("createAndSendAddInterfaceEvent: successfully sent " 
                                        + " addInterface event for interface: " + ipaddr 
                                        + " node: " + nodeLabel);
                }
                catch(Throwable t)
                {
                        log.warn("run: unexpected throwable exception caught during send to middleware", t);
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent( txNo, callerUei,
                                           "caught unexpected throwable exception.", status, "OpenNMS.Capsd");
                        }
                }
        }                
       
        /**
         * This method is responsible for generating a deleteInterface  event 
         * and sending it to eventd..
         *
         * @param nodeLabel     the node label of the node where the interface resides.
         * @param ipaddr        IP address of the interface to be deleted.
         * @param hostName      the Host server name.
         * @param txNo          the external transaction No.
         * @param callerUei     the uei of the caller event
         */
        private void createAndSendDeleteInterfaceEvent( String nodeLabel, String ipaddr, String hostName, 
                                                        long txNo, String callerUei)
        {
		Category log = ThreadCategory.getInstance(getClass());
		if (log.isDebugEnabled())
			log.debug("createAndSendDeleteInterfaceEvent:  processing updateServer event for interface:  " 
                                + ipaddr + " on server: " + hostName);
        
                Event newEvent = new Event();
                newEvent.setUei(EventConstants.DELETE_INTERFACE_EVENT_UEI);
                newEvent.setSource("OpenNMS.capsd");
                newEvent.setInterface(ipaddr);
                newEvent.setHost(hostName);
                newEvent.setTime(EventConstants.formatToString(new java.util.Date()));

                // Add appropriate parms
                Parms eventParms = new Parms();
                Parm eventParm = null;
                Value parmValue = null;

                eventParm = new Parm();
                eventParm.setParmName(EventConstants.PARM_NODE_LABEL);
                parmValue = new Value();
                parmValue.setContent(nodeLabel);
                eventParm.setValue(parmValue);
                eventParms.addParm(eventParm);

                eventParm = new Parm();
                eventParm.setParmName(EventConstants.PARM_TRANSACTION_NO);
                parmValue = new Value();
                parmValue.setContent((new Long(txNo)).toString());
                eventParm.setValue(parmValue);
                eventParms.addParm(eventParm);
                
                // Add Parms to the event
                newEvent.setParms(eventParms);
                
                // Send event to Eventd
                try
                {
                        EventIpcManagerFactory.getInstance().getManager().sendNow(newEvent);

                        if (log.isDebugEnabled())
                                log.debug("createdAndSendDeleteInterfaceEvent: successfully sent " 
                                        + " deleteInterface event for interface: " + ipaddr 
                                        + " node: " + nodeLabel);
                }
                catch(Throwable t)
                {
                        log.warn("run: unexpected throwable exception caught during send to middleware", t);
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent( txNo, callerUei,
                                           "caught unexpected throwable exception.", status, "OpenNMS.Capsd");
                        }
                }
        }                

        
	/**
	 * Process the event,  add or remove a specified interface/service pair into the database.
         * this event will cause an changeService event with the specified action. An 'action' 
         * parameter wraped in the event will tell which action to take to the service on the 
         * specified interface. The ipaddress of the interface, the service name must be included 
         * in the event.
	 *
	 * @param event	The event to process.
	 */
	private void updateServiceHandler(Event event)
	{
	        String ipaddr = event.getInterface();
                String serviceName = event.getService();
                String sourceUei = event.getUei();

                Category log = ThreadCategory.getInstance(getClass());
		if (log.isDebugEnabled())
			log.debug("updateServiceHandler:  processing updateService event for : " + serviceName
                                + " on : " + ipaddr);

		// Extract action from the event parms
		String action = null;
                long txNo = -1L;
		Parms parms = event.getParms();
		if (parms != null)
		{
                                
			String parmName = null;
			Value parmValue = null;
			String parmContent = null;
		
			Enumeration parmEnum = parms.enumerateParm();
			while(parmEnum.hasMoreElements())
			{
				Parm parm = (Parm)parmEnum.nextElement();
				parmName  = parm.getParmName();
				parmValue = parm.getValue();
				if (parmValue == null)
					continue;
                                else 
					parmContent = parmValue.getContent();
				//  get the action 
				if (parmName.equals(EventConstants.PARM_ACTION))
				{
					action = parmContent;
		                        if (log.isDebugEnabled())
			                        log.debug("updateServiceHandler:  ParmName:" + parmName 
                                                + " / ParmContent: " + parmContent);
				}
				else if (parmName.equals(EventConstants.PARM_TRANSACTION_NO))
                                {
                                        String temp = parmContent;
		                        if (log.isDebugEnabled())
			                        log.debug("updateServiceHandler:  parmName: " + parmName
                                                        + " /parmContent: " + parmContent);
                                        try
                                        {
                                                txNo = Long.valueOf(temp).longValue();
                                        }
                                        catch (NumberFormatException nfe)
                                        {
                                                log.warn("updateServiceHandler: Parameter " + EventConstants.PARM_TRANSACTION_NO 
                                                        + " cannot be non-numberic", nfe);
                                                txNo = -1L;
                                        }
                                }
						
			}
		}
                
                // Notify the external xmlrpc server receiving of the event.
                //
                if (m_xmlrpc)
                {
                        int status = EventConstants.XMLRPC_NOTIFY_RECEIVED;
                        StringBuffer message = new StringBuffer("Received event: updateService/action --");
                        message.append(action).append("  ").append(serviceName);
                        XmlrpcUtil.createAndSendXmlrpcNotificationEvent( txNo, sourceUei, 
                                message.toString(), status, "OpenNMS.Capsd");
                }
                
                boolean invalidParameters = ((ipaddr == null) || (serviceName == null) || (action == null));
                if (m_xmlrpc)
                        invalidParameters = invalidParameters || (txNo == -1L);

                if (invalidParameters)
                {
		        if (log.isDebugEnabled())
		                log.debug("updateServiceHandler:  Invalid parameters." );
                        
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent( txNo, sourceUei,
                                           "Invalid parameters.", status, "OpenNMS.Capsd");
                        }
                        
			return;
		}

		java.sql.Connection dbConn = null;
		PreparedStatement stmt = null;
		try
		{
			dbConn = DatabaseConnectionFactory.getInstance().getConnection();

                        // Retrieve the serviceId and verify if the specified service is valid.
			stmt = dbConn.prepareStatement(SQL_RETRIEVE_SERVICE_ID);
	
			stmt.setString(1, serviceName);
	
			ResultSet rs = stmt.executeQuery();
                        int  serviceId = -1;
			while(rs.next())
			{
				if (log.isDebugEnabled())
					log.debug("updateServiceHandler: retrieve serviceid for service " + serviceName);
                                serviceId = rs.getInt(1); 
			}
                        
                        if (serviceId < 0)
                        {
				if (log.isDebugEnabled())
					log.debug("updateServiceHandler: the specified service: " 
                                                + serviceName + " does not exist in the database.");
                                if (m_xmlrpc)
                                {
                                        int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                        StringBuffer message = new StringBuffer("The specified service: ").append(serviceName);
                                        message.append(" does not exist in the database.");
                                        XmlrpcUtil.createAndSendXmlrpcNotificationEvent( txNo, sourceUei,
                                                message.toString(), status, "OpenNMS.Capsd");
                                }
                                return;
                        }
                        
                        stmt.close();

                        // Verify if the specified service already exists on the interface/service mapping.        
			stmt = dbConn.prepareStatement(SQL_QUERY_SERVICE_MAPPING_EXIST);
	
			stmt.setString(1, ipaddr);
			stmt.setString(2, serviceName);
	
			rs = stmt.executeQuery();
			while(rs.next())
			{
				if (log.isDebugEnabled())
				{
					log.debug("updateService: service " + serviceName  + " on IPAddress " 
                                        + ipaddr + " already exists in the database.");
				}
                                
                                // The service exists on the interface/service mapping, a 'DELETE operation could be 
                                // performed. Just return for the 'ADD' operation.
                                if (action.equalsIgnoreCase("DELETE"))
                                {
				        if (log.isDebugEnabled())
				        {
					        log.debug("updateServiceHandler: delete service: " + serviceName  
                                                + " on IPAddress: " + ipaddr);
				        }
			                stmt = dbConn.prepareStatement(SQL_DELETE_SERVICE_INTERFACE_MAPPING);
	
			                stmt.setString(1, ipaddr);
			                stmt.setString(2, serviceName);
	
			                stmt.executeUpdate();
                                        
                                        //Create a changeService event to eventd. 
                                        createAndSendChangeServiceEvent(ipaddr, serviceName, action, txNo, sourceUei);
                                }
			        else // could not perform 'ADD' since the service already exists in the mapping.
                                {
                                        log.warn("updateServiceHandler: could not add an existing service in.");
                                        if (m_xmlrpc)
                                        {
                                                int status = EventConstants.XMLRPC_NOTIFY_SUCCESS;
                                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent( txNo, sourceUei,
                                                        "Service already exists.", status, "OpenNMS.Capsd");
                                        }
                                }
                                
                                return;
			}
                        stmt.close();
                        
                        // the service does not exist in the interface/service mapping yet, an 'ADD' operation could
                        // be performed. For other operations, log error message.
                        if (action.equalsIgnoreCase("ADD"))
                        {
                                stmt = dbConn.prepareStatement(SQL_ADD_SERVICE_TO_MAPPING);

                                stmt.setString(1, ipaddr);
                                stmt.setString(2, serviceName);
                                stmt.executeUpdate();
                                
			        if (log.isDebugEnabled())
			        {
				        log.debug("updateServiceHandler: add service " + serviceName  
                                               + " to interface: " + ipaddr);
			        }

                                //Create a changeService event to eventd. 
                                createAndSendChangeServiceEvent(ipaddr, serviceName,  action, txNo, sourceUei);

                        }
                        else 
                        {
                                log.error("updateServiceHandler: could not delete non-existing service.");
                                if (m_xmlrpc)
                                {
                                        int status = EventConstants.XMLRPC_NOTIFY_SUCCESS;
                                        StringBuffer message = new StringBuffer("Non-existing service: ");
                                        message.append(serviceName);
                                        XmlrpcUtil.createAndSendXmlrpcNotificationEvent( txNo, sourceUei,
                                                message.toString(), status, "OpenNMS.Capsd");
                                }
                        }
		}
		catch(SQLException sqlE)
		{
			log.error("SQLException during updateService on database.", sqlE);
                        if (m_xmlrpc)
                        {
                                int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                                XmlrpcUtil.createAndSendXmlrpcNotificationEvent( txNo, sourceUei,
                                           sqlE.getMessage(), status, "OpenNMS.Capsd");
                        }
		}
		finally
		{
			// close the statement
			if (stmt != null)
				try { stmt.close(); } catch(SQLException sqlE) { };

			// close the connection
			if (dbConn != null)
				try { dbConn.close(); } catch(SQLException sqlE) { };					
		}
		
	}
        
       
        /**
         * This method is responsible for generating a changeService  event 
         * and sending it to eventd..
         *
         * @param ipaddr        IP address of the interface where the service resides.
         * @param service       the service to be changed(add or remove).
         * @param action        what operation to perform for the service/interface pair.
         * @param txNo          the external transaction No.
         * @param callerUei     the uei of the caller event.
         */
        private void createAndSendChangeServiceEvent( String ipaddr, String service, String action, 
                                                      long txNo, String callerUei)
        {
		Category log = ThreadCategory.getInstance(getClass());
		if (log.isDebugEnabled())
			log.debug("createAndSendChangeServiceEvent:  processing updateService event for service:  " 
                                + service + " on interface: " + ipaddr);
        
                Event newEvent = new Event();
                newEvent.setUei(EventConstants.CHANGE_SERVICE_EVENT_UEI);
                newEvent.setSource("OpenNMS.capsd");
                newEvent.setInterface(ipaddr);
                newEvent.setService(service);
                newEvent.setTime(EventConstants.formatToString(new java.util.Date()));

                // Add appropriate parms
                Parms eventParms = new Parms();
                Parm eventParm = null;
                Value parmValue = null;

                eventParm = new Parm();
                eventParm.setParmName(EventConstants.PARM_ACTION);
                parmValue = new Value();
                parmValue.setContent(action);
                eventParm.setValue(parmValue);
                eventParms.addParm(eventParm);

                eventParm = new Parm();
                eventParm.setParmName(EventConstants.PARM_TRANSACTION_NO);
                parmValue = new Value();
                parmValue.setContent((new Long(txNo)).toString());
                eventParm.setValue(parmValue);
                eventParms.addParm(eventParm);
                
                // Add Parms to the event
                newEvent.setParms(eventParms);
                
                // Send event to Eventd
                try
                {
                        EventIpcManagerFactory.getInstance().getManager().sendNow(newEvent);

                        if (log.isDebugEnabled())
                                log.debug("createAndSendChangeServiceEvent: successfully sent " 
                                        + " changeService event service: " + service 
                                        + " on interface: " + ipaddr);
                }
                catch(Throwable t)
                {
                        log.warn("run: unexpected throwable exception caught during send to middleware", t);
                        int status = EventConstants.XMLRPC_NOTIFY_FAILURE;
                        XmlrpcUtil.createAndSendXmlrpcNotificationEvent( txNo, callerUei,
                                   "caught unexpected throwable exception.", status, "OpenNMS.Capsd");
                }
        }                
        
        /**
	 * This method is invoked by the EventIpcManager
	 * when a new event is available for processing. Currently
	 * only text based messages are processed by this callback.
	 * Each message is examined for its Universal Event Identifier
	 * and the appropriate action is taking based on each UEI.
	 *
	 * @param event	The event.
	 */
	public void onEvent(Event event)
	{
		Category log = ThreadCategory.getInstance(getClass());

		String eventUei = event.getUei();
		if (eventUei == null)
		{
			return;
		}
		
		if (log.isDebugEnabled())
		{
			log.debug("Received event: " + eventUei);
		}

		if(eventUei.equals(EventConstants.NEW_SUSPECT_INTERFACE_EVENT_UEI))
		{
			// new poll event
			try
			{
				if (log.isDebugEnabled())
				{
					log.debug("onMessage: Adding interface to suspectInterface Q: " + event.getInterface());
				}
				m_suspectQ.add(new SuspectEventProcessor(event.getInterface()));
			}
			catch(Exception ex)
			{
				log.error("onMessage: Failed to add interface to suspect queue", ex);
			}
		}
		else if(eventUei.equals(EventConstants.FORCE_RESCAN_EVENT_UEI))
		{
			// If the event has a node identifier use it otherwise
			// will need to use the interface to lookup the node id
			// from the database
			int nodeid = -1;
			
			if (event.hasNodeid())
				nodeid = (int)event.getNodeid();
			else
			{
				// Extract interface from the event and use it to
				// lookup the node identifier associated with the 
				// interface from the database.
				//
			
				// Get database connection and retrieve nodeid
				Connection dbc = null;
				PreparedStatement stmt = null;
				ResultSet rs = null;
				try
				{
					dbc = DatabaseConnectionFactory.getInstance().getConnection();
				
					// Retrieve node id
					stmt = dbc.prepareStatement(SQL_RETRIEVE_NODEID);
					stmt.setString(1, event.getInterface());
					rs = stmt.executeQuery();
					if (rs.next())
					{
						nodeid = rs.getInt(1);
					}
				}
				catch (SQLException sqlE)
				{
					log.error("onMessage: Database error during nodeid retrieval for interface " + event.getInterface(), sqlE);
				}
				finally	
				{
					// Close the prepared statement
					if (stmt != null)
					{
						try
						{
							stmt.close();
						}
						catch (SQLException sqlE)
						{
							// Ignore
						}
					}
				
					// Close the connection
					if (dbc != null)
					{
						try
						{
							dbc.close();
						}
						catch (SQLException sqlE)
						{
							// Ignore
						}
					}
				}
			
				if (nodeid == -1)
				{
					log.error("onMessage: Nodeid retrieval for interface " + event.getInterface() + " failed.  Unable to perform rescan.");
					return;
				}
			}
			
			// Rescan the node.  
			m_scheduler.forceRescan(nodeid);
		}
		else if(event.getUei().equals(EventConstants.UPDATE_SERVER_EVENT_UEI))
		{
			// If there is no interface or NMS server found then it cannot be processed
			//
			if(event.getInterface() == null || event.getInterface().length() == 0
                                || event.getHost() == null || event.getHost().length()==0)
			{
				log.info("BroadcastEventProcessor: no interface or NMS host server found, discarding event");
			}
			else
			{
				updateServerHandler(event);
			}
                }
		else if(event.getUei().equals(EventConstants.UPDATE_SERVICE_EVENT_UEI))
		{
			// If there is no interface, or service found,  
                        // then it cannot be processed
			//
			if(event.getInterface() == null || event.getInterface().length() == 0
                                || event.getService() == null || event.getService().length()==0)
			{
				log.info("BroadcastEventProcessor: no interface, NMS host server,"
                                        + " or service found, discarding event");
			}
			else
			{
				updateServiceHandler(event);
			}
                }
		else if(event.getUei().equals(EventConstants.ADD_NODE_EVENT_UEI))
		{
			// If there is no interface then it cannot be processed
			//
			if(event.getInterface() == null || event.getInterface().length() == 0)
			{
				log.info("BroadcastEventProcessor: no interface found, discarding event");
			}
			else
			{
				addNodeHandler(event);
			}
                }
                else if(event.getUei().equals(EventConstants.DELETE_NODE_EVENT_UEI))
		{
			deleteNodeHandler(event);
                }
		else if(event.getUei().equals(EventConstants.ADD_INTERFACE_EVENT_UEI))
		{
			// If there is no interface then it cannot be processed
			//
			if(event.getInterface() == null || event.getInterface().length() == 0)
			{
				log.info("BroadcastEventProcessor: no interface found, discarding event");
			}
			else
			{
				addInterfaceHandler(event);
			}
                }
		else if(event.getUei().equals(EventConstants.DELETE_INTERFACE_EVENT_UEI))
		{
			// If there is no interface then it cannot be processed
			//
			if(event.getInterface() == null || event.getInterface().length() == 0)
			{
				log.info("BroadcastEventProcessor: no interface found, discarding event");
			}
			else
			{
				deleteInterfaceHandler(event);
			}
                }
		else if(event.getUei().equals(EventConstants.CHANGE_SERVICE_EVENT_UEI))
		{
			// If there is no interface or service then it cannot be processed
			//
			if(event.getInterface() == null || event.getInterface().length() == 0
                                || event.getService() == null || event.getService().length() == 0)
			{
				log.info("BroadcastEventProcessor: no interface found, discarding event");
			}
			else
			{
				changeServiceHandler(event);
			}
                }
		else if(eventUei.equals(EventConstants.NODE_ADDED_EVENT_UEI))
		{
			// Schedule the new node.
			try
			{
				m_scheduler.scheduleNode((int)event.getNodeid());
			}
			catch(SQLException sqlE)
			{
				log.error("onMessage: SQL exception while attempting to schedule node " + event.getNodeid(), sqlE);
			}
		}
		else if(eventUei.equals(EventConstants.NODE_DELETED_EVENT_UEI))
		{
			// Remove the deleted node from the scheduler
			m_scheduler.unscheduleNode((int)event.getNodeid());
		}
		else if(eventUei.equals(EventConstants.DUP_NODE_DELETED_EVENT_UEI))
		{
			// Remove the deleted node from the scheduler
			m_scheduler.unscheduleNode((int)event.getNodeid());
		}
	} // end onEvent()

	/**
	 * Return an id for this event listener
	 */
	public String getName()
	{
		return "Capsd:BroadcastEventProcessor";
	}

} // end class

