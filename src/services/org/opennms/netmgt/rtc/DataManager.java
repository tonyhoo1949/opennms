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
// 2003 Jan 31: Cleaned up some unused imports.
// 2002 Oct 24: Replaced references to HashTable with HashMap.
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

package org.opennms.netmgt.rtc;

import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.apache.log4j.Category;
import org.exolab.castor.xml.MarshalException;
import org.exolab.castor.xml.ValidationException;
import org.opennms.core.utils.ThreadCategory;
import org.opennms.netmgt.EventConstants;
import org.opennms.netmgt.config.CategoryFactory;
import org.opennms.netmgt.config.DatabaseConnectionFactory;
import org.opennms.netmgt.config.categories.Categories;
import org.opennms.netmgt.config.categories.Categorygroup;
import org.opennms.netmgt.filter.Filter;
import org.opennms.netmgt.filter.FilterParseException;
import org.opennms.netmgt.rtc.datablock.RTCCategory;
import org.opennms.netmgt.rtc.datablock.RTCHashMap;
import org.opennms.netmgt.rtc.datablock.RTCNode;
import org.opennms.netmgt.rtc.datablock.RTCNodeKey;
import org.xml.sax.SAXException;

/**
 * <pre>The DataManager contains and maintains all the data for the RTC.
 *
 * The basic datablock is a 'RTCNode' that gets added to relevant 'RTCCategory's.
 * it also gets added to a map with different keys for easy lookup
 *
 * The map('RTCHashMap') is keyed with 'RTCNodeKey's(a nodeid/ip/svc combination),
 * nodeid/ip combinations and nodeid and these keys either lookup a single RTCNode or
 * lists of 'RTCNode's
 *
 * Incoming events have a method in the DataManager to alter data - for e.g.,
 * a 'nodeGainedService' event would result in the 'nodeGainedService()' method
 * being called by the DataUpdater(s).
 *
 * @author 	<A HREF="mailto:sowmya@opennms.org">Sowmya Nataraj</A>
 * @author	<A HREF="http://www.opennms.org">OpenNMS.org</A>
 */
public class DataManager extends Object
{
	/**
	 * The RTC categories
	 */
	private Map			m_categories;

	/**
	 * map keyed using the RTCNodeKey or nodeid or nodeid/ip
	 */
	private RTCHashMap		m_map;

	/**
	 * The service table map - this is built at startup and updated if a
	 * servicename that is not found in the map is found so as to avoid a
	 * database lookup for each servicename to serviceid mapping
	 */
	private  HashMap		m_serviceTableMap;

	/**
	 * Get the 'ismanaged' status for the nodeid, ipaddr combination
	 *
	 * @param nodeid	the nodeid of the interface
	 * @param ip		the interface for which the status is required
	 * @param svc		the service for which status is required
	 *
	 * @return 	the 'status' from the ifservices table
	 */
	private char getServiceStatus(long nodeid, String ip, String svc)
	{
		//
		// check the 'status' flag
		//
		char status='\0';
		ResultSet  statusRS=null;

		Connection dbConn = null;
		try
		{
			dbConn = DatabaseConnectionFactory.getInstance().getConnection();

			// Prepare statement to get the 'status' flag for a nodeid/IP/service
			PreparedStatement svcStatusGetStmt = dbConn.prepareStatement(RTCConstants.DB_GET_SERVICE_STATUS);
		
			svcStatusGetStmt.setLong(1, nodeid);
			svcStatusGetStmt.setString(2, ip);
			svcStatusGetStmt.setLong(3, getServiceID(svc));
			statusRS = svcStatusGetStmt.executeQuery();
			if (statusRS.next())
			{
				String statusStr = statusRS.getString(1); 
				status = statusStr.charAt(0);
			}

			// close statement
			svcStatusGetStmt.close();
		}
		catch (SQLException ipe)		
		{
			Category log = ThreadCategory.getInstance(DataManager.class);
			log.warn("Error reading status for: " + nodeid + "/" + ip + "/" + svc, ipe);

			status = '\0';
		}
		finally
		{
			try
			{
				if (statusRS != null)
					statusRS.close();
			}
			catch (Exception e)
			{
				Category log = ThreadCategory.getInstance(DataManager.class);
				if (log.isDebugEnabled())
					log.debug("Exception while closing the service status result set",e);
			}

			try
			{
				if(dbConn != null)
					dbConn.close();
			}
			catch(SQLException e)
			{
				ThreadCategory.getInstance(getClass()).warn("Exception closing JDBC connection", e);
			}
		
		}

		return status;
	}

	/**
	 * Add a node/ip/service to the specified category 
	 *
	 * @param nodeid	the nodeid to be added
	 * @param ip		the interface to be added
	 * @param service	the service to be added
	 * @param cat		the category to which this node is to be added to
	 * @param knownIPs	the hashtable of IP->list of RTCNodes (used only at startup)
	 * @param outagesGetStmt the prepared statement to read outages
	 */
	private void addNodeIpSvcToCategory(long nodeid, String ip, String svcname,
						RTCCategory cat,
						HashMap knownIPs,
						PreparedStatement outagesGetStmt)
	{
		Category log = ThreadCategory.getInstance(DataManager.class);

		//
		// check if the node is already part of the tree, if yes,
		// simply add the current category information 
		//
		RTCNodeKey key = new RTCNodeKey(nodeid, ip, svcname);
		RTCNode rtcN = (RTCNode)m_map.get(key);
		if (rtcN != null)
		{
			// add the category info to the node
			rtcN.addCategory(cat.getLabel());

			// Add node to category
			cat.addNode(rtcN);

			if(log.isDebugEnabled())
				log.debug("rtcN : " + rtcN.getNodeID() + "/"
					  + rtcN.getIP() + "/" + rtcN.getSvcName()
					  + " added to cat: " 
					  +  cat.getLabel());

			return;
		}

		// create the node
		rtcN = new RTCNode(nodeid, ip, svcname);

		// add the category info to the node
		rtcN.addCategory(cat.getLabel());

		// read outages
		//
		// the window for which outages are to be read - the current
		// time minus the rollingWindow
		//
		long window = (new java.util.Date()).getTime() - RTCManager.getRollingWindow();
		Timestamp windowTS = new Timestamp(window);

		//
		// Read closed outages in the above window and outages that are
		// still open
		//
		ResultSet  outRS=null;
		try
		{
			//
			// get outages 
			//
			outagesGetStmt.setLong(1, nodeid);
			outagesGetStmt.setString(2, ip);
			outagesGetStmt.setLong(3, getServiceID(svcname));
			outagesGetStmt.setTimestamp(4, windowTS);
			outagesGetStmt.setTimestamp(5, windowTS);
			outRS = outagesGetStmt.executeQuery();
			while(outRS.next())
			{
				int outColIndex=1;
				Timestamp lostTimeTS = outRS.getTimestamp(outColIndex++);
				Timestamp regainedTimeTS = outRS.getTimestamp(outColIndex++);

				long lostTime = lostTimeTS.getTime();
				long regainedTime = -1;
				if (regainedTimeTS != null)
					regainedTime = regainedTimeTS.getTime();

				if(log.isDebugEnabled())
				{
					log.debug("lost time for nodeid/ip/svc: " + nodeid + "/"
						  + ip + "/" + svcname + ": " + 
						  lostTimeTS + "/" + lostTime);

					log.debug("regained time for nodeid/ip/svc: " + nodeid + "/"
						  + ip + "/" + svcname + ": " + 
						  regainedTimeTS + "/" + regainedTime);
				}

				rtcN.addSvcTime(lostTime, regainedTime);
			}
		}
		catch (SQLException sqle2)		
		{
			if(log.isDebugEnabled())
				log.debug("Error getting outages information for nodeid: " + nodeid + "\tip:" + ip, sqle2);

		}
		catch (Exception e2)		
		{
			if(log.isDebugEnabled())
				log.debug("Unknown error while reading outages for nodeid: " + nodeid + "\tip: " + ip, e2);

		}
		finally
		{
			// finally close the result set
			try
			{
				if (outRS != null)
					outRS.close();
			}
			catch(Exception e)
			{
				if(log.isDebugEnabled())
					log.debug("Exception while closing the outages result set ", e);
			}
		}

		// Add node to the map
		m_map.put(key, rtcN);

		// node key map
		m_map.add(nodeid, rtcN);

		// node and ip key map
		m_map.add(nodeid, ip,  rtcN);

		// Add node to category
		cat.addNode(rtcN);

		// Add node to the knownIPs
		if(knownIPs != null)
		{
			List rtcS = (List)knownIPs.get(ip);
			if (rtcS == null)
			{
				rtcS = new ArrayList();
				rtcS.add(rtcN);
			}
			else
			{
				if(!rtcS.contains(rtcN))
					rtcS.add(rtcN);
			}
		}

		if(log.isDebugEnabled())
			log.debug("rtcN : " + rtcN.getNodeID() + "/"
				  + rtcN.getIP() + "/" + rtcN.getSvcName()
				  + " added to cat: " + cat.getLabel() );

	}

	/**
	 * Add the RTCNodes known for an IP to the category
	 *
	 * @param ipRTCs	the list of RTCNodes related to a particular IP
	 * @param cat		the category to which the list is to be added
	 */
	private void addIpToCategory(List ipRTCs, RTCCategory cat)
	{
		if (ipRTCs == null)
			return;

		Category log = ThreadCategory.getInstance(DataManager.class);

		Iterator rtcIter = ipRTCs.iterator();
		while(rtcIter.hasNext())
		{
			RTCNode rtcN = (RTCNode)rtcIter.next();

			// Check if this service is reqd. to be added for this category
			String svcName = rtcN.getSvcName();
			if (!cat.containsService(svcName))
			{
				if(log.isDebugEnabled())
					log.debug("service " + svcName
					  + " not in category service list of cat: "
					  +  cat.getLabel() + " - skipping "
					  + rtcN.getNodeID() + "\t" + rtcN.getIP() + "\t" + svcName);

				continue;
			}

			// Add cat info to the node
			rtcN.addCategory(cat.getLabel());

			// Add node to category
			cat.addNode(rtcN);

			if(log.isDebugEnabled())
				log.debug("rtcN : " + rtcN.getNodeID() + "/" 
					  + rtcN.getIP() + "/" + rtcN.getSvcName() 
					  + " added to cat: " + cat.getLabel());
			
		}

	}

	/**
	 * Read the categories from the categories.xml and create the 'RTCCategory's map
	 */
	private void createCategoriesMap()
	{
		org.apache.log4j.Category log = ThreadCategory.getInstance(DataManager.class);

		CategoryFactory cFactory = null;
		try
		{
			CategoryFactory.reload();
			cFactory = CategoryFactory.getInstance();

		}
		catch(IOException ex)
		{
			log.error("Failed to load categories information", ex);
			throw new UndeclaredThrowableException(ex);
		}
		catch(MarshalException ex)
		{
			log.error("Failed to load categories information", ex);
			throw new UndeclaredThrowableException(ex);
		}
		catch(ValidationException ex)
		{
			log.error("Failed to load categories information", ex);
			throw new UndeclaredThrowableException(ex);
		}

		m_categories = new HashMap();
		
		Enumeration enumCG = cFactory.getConfig().enumerateCategorygroup();
		while(enumCG.hasMoreElements())
		{
			Categorygroup cg = (Categorygroup)enumCG.nextElement();

			String commonRule = cg.getCommon().getRule();
			
			Categories cats = cg.getCategories();

			Enumeration enumCat = cats.enumerateCategory();
			while(enumCat.hasMoreElements())
			{
				org.opennms.netmgt.config.categories.Category cat = (org.opennms.netmgt.config.categories.Category)enumCat.nextElement();
				
				RTCCategory rtcCat = new RTCCategory(cat, commonRule);
				m_categories.put(rtcCat.getLabel(), rtcCat);
			}
		}
	}

	/**
	 * For each category in the categories list, this reads the 
	 * services and outage tables to get the initial data, creates
	 * 'RTCNode' objects that are added to the map and and to the
	 * appropriate category
	 *
	 * @throws SQLException if the database read fails due to an SQL error
	 * @throws FilterParseException if filtering the data against the category rule fails due to the rule being incorrect
	 * @throws RTCException if the database read or filtering the data against the category rule fails for some reason
	 */
	private void populateNodesFromDB(Connection dbConn)
			throws SQLException, FilterParseException, RTCException
	{
		// create a hashtable of IP->RTCNodes list for startup to save on database access
		HashMap knownIPs	= new HashMap();

		// Create the filter
		Filter filter =  new Filter();

		// Prepare the statement to get service entries for each IP
		PreparedStatement servicesGetStmt = dbConn.prepareStatement(RTCConstants.DB_GET_SVC_ENTRIES);
	 	// Prepared statement to get node info for an ip
		PreparedStatement ipInfoGetStmt	=  dbConn.prepareStatement(RTCConstants.DB_GET_INFO_FOR_IP);
	 	// Prepared statement to get outages entries
		PreparedStatement outagesGetStmt = dbConn.prepareStatement(RTCConstants.DB_GET_OUTAGE_ENTRIES);

		Category log = ThreadCategory.getInstance(DataManager.class);

		// loop through the categories

		Iterator catIter = m_categories.values().iterator();
		while(catIter.hasNext())
		{
			RTCCategory cat = (RTCCategory)catIter.next();

			// get the rule for this category, get the list of nodes that satisfy this rule
			String filterRule = cat.getEffectiveRule();

			if(log.isDebugEnabled())
				log.debug("Category: " + cat.getLabel() + "\t" + filterRule);

			String ip = null;
			ResultSet ipRS=null;
			try
			{
				List nodeIPs = filter.getIPList(filterRule);

				if (log.isDebugEnabled())
					log.debug("Number of IPs satisfying rule: " + nodeIPs.size());
				
				// For each of these IP addresses, get the details from the ifServices and services tables
				Iterator ipIter = nodeIPs.iterator();
				while(ipIter.hasNext())
				{
					ip = (String)ipIter.next();

					if(log.isDebugEnabled())
						log.debug("IP: " + ip);

					// check if this ip is something we already know about
					// so we can avoid database lookups
					List ipRTCs = (List)knownIPs.get(ip);
					if (ipRTCs != null)
					{
						addIpToCategory(ipRTCs, cat);
						continue;
					}

					// get node info for this ip
					ipInfoGetStmt.setString(1, ip);

					ipRS = ipInfoGetStmt.executeQuery();
					while(ipRS.next())
					{
						int nodeColIndex = 1;

						long nodeid = ipRS.getLong(nodeColIndex++);

						if(log.isDebugEnabled())
							log.debug("IP->node info lookup result: " + nodeid);

						//
						// get the services for this IP address
						//
						ResultSet  svcRS=null;
						servicesGetStmt.setLong(1, nodeid);
						servicesGetStmt.setString(2, ip);
						svcRS = servicesGetStmt.executeQuery();

						// create node objects for this nodeID/IP/service
						while(svcRS.next())
						{
							int colIndex = 1;
						
							// read data from the resultset
							String svcname = svcRS.getString(colIndex++);

							if(log.isDebugEnabled())
								log.debug("services result: " + nodeid + "\t" + ip + "\t" +  svcname);

							// unless the service found is in the category's services list,
							// do not add that service
							if (!cat.containsService(svcname))
							{
								if(log.isDebugEnabled())
									log.debug("service " + svcname 
										  + " not in category service list of cat " 
										  + cat.getLabel() + " - skipping " 
										  + nodeid + "\t" + ip + "\t" + svcname);
								continue;
							}

							addNodeIpSvcToCategory(nodeid, ip, svcname, cat, knownIPs, outagesGetStmt);
						}

						// finally close the result set
						try
						{
							if (svcRS != null)
								svcRS.close();
						}
						catch(Exception e)
						{
							if(log.isDebugEnabled())
								log.debug("Exception while closing the services result set", e);
						}
					}
				}

			}
			catch(SQLException e)
			{
				if(log.isDebugEnabled())
					log.debug("Unable to get node list for category \'" + cat.getLabel(), e);
				throw e;
			}
			catch(FilterParseException e)
			{
				// if we get here, the error was most likely in
				// getting the nodelist from the filters
				if(log.isDebugEnabled())
					log.debug("Unable to get node list for category \'" + cat.getLabel(), e);

				// throw exception
				throw e;
			}
			catch(Exception e)
			{
				if(log.isDebugEnabled())
					log.debug("Unable to get node list for category \'" + cat.getLabel(), e);

				// throw rtc exception
				throw new RTCException("Unable to get node list for category \'" + cat.getLabel() + "\':\n\t" + e.getMessage());
			}
			finally
			{
				try
				{
					if(ipRS != null)
						ipRS.close();
				}
				catch(Exception e)
				{
					if(log.isDebugEnabled())
						log.debug("Exception while closing the ip get node info result set - ip: " + ip, e);
				}
			}
		}

		//
		// close the prepared statements
		//
		try
		{
			if (servicesGetStmt != null)
				servicesGetStmt.close();

			if (ipInfoGetStmt != null)
				ipInfoGetStmt.close();

			if (outagesGetStmt != null)
				outagesGetStmt.close();
		}
		catch (SQLException csqle)
		{
			if (log.isDebugEnabled())
				log.debug("Exception while closing the prepared statements after populating nodes from DB");

			// do nothing
		}
	}

	/**
	 * Constructor  - it parses categories from the categories.xml and
	 * populates them with 'RTCNode' objects created from data read from
	 * the database (services and outage tables)
	 *
	 * @exception SQLException thrown if there is an error reading initial data from the database
	 * @exception FilterParseException thrown if a rule in the categories.xml was incorrect
	 * @exception RTCException thrown if the initialization/data reading does not go through
	 */
	public DataManager()
		throws SAXException,
		       IOException,
		       SQLException,
		       FilterParseException,
		       RTCException
	{
		Category log = ThreadCategory.getInstance(DataManager.class);

		java.sql.Connection dbConn = null;
		try
		{
			DatabaseConnectionFactory.reload();
			dbConn = DatabaseConnectionFactory.getInstance().getConnection();
		}
		catch(IOException ex)
		{
			log.warn("Failed to load database config", ex);
			throw new UndeclaredThrowableException(ex);
		}
		catch(MarshalException ex)
		{
			log.warn("Failed to unmarshal database config", ex);
			throw new UndeclaredThrowableException(ex);
		}
		catch(ValidationException ex)
		{
			log.warn("Failed to unmarshal database config", ex);
			throw new UndeclaredThrowableException(ex);
		}
		catch(SQLException ex)
		{
			log.warn("Failed to get database connection", ex);
			throw new UndeclaredThrowableException(ex);
		}
		catch(ClassNotFoundException ex)
		{
			log.warn("Failed to get database connection", ex);
			throw new UndeclaredThrowableException(ex);
		}

		// read the categories.xml to get all the categories
		createCategoriesMap();

		if (m_categories == null || m_categories.isEmpty())
		{
			throw new RTCException("No categories found in categories.xml");
		}

		if(log.isDebugEnabled())
			log.debug("Number of categories read: " + m_categories.size());

		// create data holder
		m_map = new RTCHashMap(30000);

		// create the service tbale map
		m_serviceTableMap = new HashMap();
		PreparedStatement stmt = dbConn.prepareStatement(RTCConstants.SQL_DB_SVC_TABLE_READ);
		ResultSet rset = stmt.executeQuery();
		while(rset.next())
		{
			long svcid     = rset.getLong(1);
			String svcname = rset.getString(2);
			
			m_serviceTableMap.put(svcname, new Long(svcid));
		}

		rset.close();
		stmt.close();

		// Populate the nodes initially from the database
		populateNodesFromDB(dbConn);

		// close the database connection
		try
		{
			if(dbConn != null)
				dbConn.close();
		}
		catch(SQLException e)
		{
			ThreadCategory.getInstance(getClass()).warn("Exception closing JDBC connection", e);
		}
	}

	/**
	 * Add a new entry to the map and the categories on a 'serviceGained' event
	 *
	 * @param  nodeid	the node id
	 * @param  ip		the IP address
	 * @param  svcName	the service name
	 *
	 */
	public synchronized void nodeGainedService(long nodeid, String ip, String svcName)
	{
		Category log = ThreadCategory.getInstance(DataManager.class);

		//
		// check the 'status' flag for the service 
		//
		char svcStatus =  getServiceStatus(nodeid, ip, svcName);

		//
		// Include only service status 'A'
		//
		if (svcStatus != 'A')
		{
			if (log.isInfoEnabled())
				log.info("nodeGainedSvc: " + nodeid + "/" + ip + "/" + svcName + " IGNORED because status is not active: " + svcStatus);

			return;
		}

		if (log.isDebugEnabled())
			log.debug("nodeGainedSvc: " + nodeid + "/" + ip + "/" + svcName + "/" + svcStatus);

		// create the key for this node
		RTCNodeKey key = new RTCNodeKey(nodeid, ip, svcName);

		// create the node
		RTCNode rtcN = new RTCNode(nodeid, ip, svcName);

		// add to the data holder
		m_map.put(key,  rtcN);
		m_map.add(nodeid, rtcN);
		m_map.add(nodeid, ip, rtcN);

		// filter to check if this satisfies category rules
		Filter filter =  new Filter();

		// now go through the categories and add to appropriate
		// ones
		Iterator iter = m_categories.values().iterator();
		while(iter.hasNext())
		{
			RTCCategory cat = (RTCCategory)iter.next();

			// unless the service found is in the category's services list,
			// do not bother about this service
			if (!cat.containsService(svcName))
			{
				continue;
			}

			// Validate the IP for this category
			boolean nodeValidated = false;

			// Check if this IP has already been validated for this category
			nodeValidated = m_map.isIpValidated(nodeid, ip, cat.getLabel());
			if (!nodeValidated)
			{
				// get the rule for this category and see if this node satisfies the rule
				String filterRule = cat.getEffectiveRule();

				try
				{
					nodeValidated = filter.isValid(ip, filterRule);
				}
				catch(Exception e)
				{
					//  Log  error and ignore this category?
					log.warn("Unable to validate/add new node " + rtcN.toString() + " to category \'" 
						+ cat.getLabel() + "\' exception", e);
					continue;
				}
			}

			// if node did validate against this category
			// add that info
			if (nodeValidated)
			{
				// add to category
				cat.addNode(rtcN);

				// add the category info to the node
				rtcN.addCategory(cat.getLabel());

				if(log.isDebugEnabled())
				{
					log.debug("rtcN : " + rtcN.getNodeID() + "/" + rtcN.getIP() + "/" + rtcN.getSvcName() 
						  + " added to cat: " + cat.getLabel());
				}
			}

		}
	}

	/**
	 * Add a lost service entry to the right node
	 *
	 * @param  nodeid	the node id
	 * @param  ip		the IP address
	 * @param  svcName	the service name
	 * @param  t		the time at which service was lost
	 */
	public synchronized void nodeLostService(long nodeid, String ip, String svcName, long t)
	{
		RTCNodeKey key = new RTCNodeKey(nodeid, ip, svcName);
		RTCNode rtcN  = (RTCNode)m_map.get(key);
		if (rtcN == null)
		{
			// oops! got a lost/regained service for a node that is not known?
			Category log = ThreadCategory.getInstance(DataManager.class);
			log.info("Received a nodeLostService event for an unknown/irrelevant node: " + key.toString() );
			return;
		}

		// inform node
		rtcN.nodeLostService(t);

	}

	/**
	 * Add a lost service entry to the right nodes
	 *
	 * @param  nodeid	the node id
	 * @param  ip		the IP address
	 * @param  t		the time at which service was lost
	 */
	public synchronized void interfaceDown(long nodeid, String ip, long t)
	{
		String key = Long.toString(nodeid) + ip;
		List nodesList = (List)m_map.get(key);
		if (nodesList == null)
		{
			// nothing to do - node/ip probably does not belong
			// to any of the categories
			return;
		}
		
		// iterate through this list
		ListIterator listIter = nodesList.listIterator();
		while(listIter.hasNext())
		{
			RTCNode rtcN = (RTCNode)listIter.next();

			// inform node
			rtcN.nodeLostService(t);
		}
	}

	/**
	 * Add a lost service entry to the right nodes
	 *
	 * @param  nodeid	the node id
	 * @param  t		the time at which service was lost
	 */
	public synchronized void nodeDown(long nodeid, long t)
	{
		Long key = new Long(nodeid);
		List nodesList = (List)m_map.get(key);
		if (nodesList == null)
		{
			// nothing to do - node probably does not belong
			// to any of the categories
			return;
		}
		
		// iterate through this list
		ListIterator listIter = nodesList.listIterator();
		while(listIter.hasNext())
		{
			RTCNode rtcN = (RTCNode)listIter.next();

			// inform node
			rtcN.nodeLostService(t);
		}
	}

	/**
	 * Add a regained service entry to the right nodes
	 *
	 * @param  nodeid	the node id
	 * @param  t		the time at which service was regained
	 */
	public synchronized void nodeUp(long nodeid, long t)
	{
		Long key = new Long(nodeid);
		List nodesList = (List)m_map.get(key);
		if (nodesList == null)
		{
			// nothing to do - node probably does not belong
			// to any of the categories
			return;
		}
		
		// iterate through this list
		ListIterator listIter = nodesList.listIterator();
		while(listIter.hasNext())
		{
			RTCNode rtcN = (RTCNode)listIter.next();

			// inform node
			rtcN.nodeRegainedService(t);
		}
	}

	/**
	 * Add a regained service entry to the right nodes
	 *
	 * @param  nodeid	the node id
	 * @param  ip		the IP address
	 * @param  t		the time at which service was regained
	 */
	public synchronized void interfaceUp(long nodeid, String ip, long t)
	{
		String key = Long.toString(nodeid) + ip;
		List nodesList = (List)m_map.get(key);
		if (nodesList == null)
		{
			// nothing to do - node/ip probably does not belong
			// to any of the categories
			return;
		}
		
		// iterate through this list
		ListIterator listIter = nodesList.listIterator();
		while(listIter.hasNext())
		{
			RTCNode rtcN = (RTCNode)listIter.next();

			// inform node
			rtcN.nodeRegainedService(t);
		}
	}

	/**
	 * Add a regained service entry to the right node
	 *
	 * @param  nodeid	the node id
	 * @param  ip		the IP address
	 * @param  svcName	the service name
	 * @param  t		the time at which service was regained
	 */
	public synchronized void nodeRegainedService(long nodeid, String ip, String svcName, long t)
	{
		RTCNodeKey key = new RTCNodeKey(nodeid, ip, svcName);
		RTCNode rtcN  = (RTCNode)m_map.get(key);
		if (rtcN == null)
		{
			// oops! got a lost/regained service for a node that is not known?
			Category log = ThreadCategory.getInstance(DataManager.class);
			log.info("Received a nodeRegainedService event for an unknown/irrelevant node: " + key.toString() );
			return;
		}

		// inform node
		rtcN.nodeRegainedService(t);
	}

	/**
	 * Remove node from the map and the categories on a 'serviceDeleted' event
	 *
	 * @param  nodeid	the nodeid on which service was deleted
	 * @param  ip		the ip on which service was deleted
	 * @param  service	the service that was deleted
	 */
	public synchronized void serviceDeleted(long nodeid, String ip, String svcName)
	{
		// create lookup key
		RTCNodeKey key = new RTCNodeKey(nodeid, ip, svcName);

		// lookup the node
		RTCNode rtcN = (RTCNode)m_map.get(key);
		if (rtcN == null)
		{
			Category log = ThreadCategory.getInstance(DataManager.class);
			log.warn("Received a " + EventConstants.SERVICE_DELETED_EVENT_UEI + " event for an unknown node: " + key.toString());

			return;
		}

		//
		// Go through from all the categories this node belongs to
		// and delete the service
		//
		List categories = rtcN.getCategories();
		ListIterator catIter = categories.listIterator();
		while(catIter.hasNext())
		{
			String catlabel = (String)catIter.next();

			RTCCategory cat = (RTCCategory)m_categories.get(catlabel);

			// get nodes in this category
			List  catNodes = cat.getNodes();

			// check if the category contains this node
			Long tmpNodeid = new Long(rtcN.getNodeID());
			int nIndex = catNodes.indexOf(tmpNodeid);
			if (nIndex != -1)
			{
				// remove from the category
				catNodes.remove(nIndex);

				// let the node know that this category is out
				catIter.remove();
			}
		}

		// finally remove from map 
		m_map.remove(key);
		m_map.delete(rtcN.getNodeID(), rtcN);
		m_map.delete(rtcN.getNodeID(), rtcN.getIP(), rtcN);
		
		// allow for gc
		rtcN = null;
	}

	/**
	 * Reparent an interface. This effectively means updating the
	 * nodelist of the categories and the map
	 *
	 * Use the ip/oldnodeid combination to get all nodes that will
	 * be affected - for each of these nodes, remove the old entry
	 * and add a new one with new keys to the map
	 *
	 * <em>Note:</em> Each of these nodes could belong to more than
	 * one category. However, category rule evaluation is done 
	 * based ONLY on the IP - therefore changing the nodeID on the
	 * node should update the categories appropriately
	 *
	 * @param ip		the interface to reparent
	 * @param oldNodeId	the node that the ip belonged to earlier
	 * @param newNodeId	the node that the ip now belongs to
	 */
	public synchronized void interfaceReparented(String ip, long oldNodeId, long newNodeId)
	{
		// get all RTCNodes with the ip/oldNodeId
		String key = Long.toString(oldNodeId) + ip;
		List nodesList = (List)m_map.get(key);
		if (nodesList == null)
		{
			// nothing to do - simply means ip does not belong
			// to any of the categories
			return;
		}
		
		// iterate through this list
		ListIterator listIter = nodesList.listIterator();
		while(listIter.hasNext())
		{
			RTCNode rtcN = (RTCNode)listIter.next();

			// get the key for this node
			RTCNodeKey rtcnKey = new RTCNodeKey(rtcN.getNodeID(), rtcN.getIP(), rtcN.getSvcName());

			// remove the node pointed to by this key from the map
			m_map.remove(rtcnKey);

			// remove this node from the list pointed to
			// by the nodeid key
			m_map.delete(oldNodeId, rtcN);

			// remove from current list pointed to by the iterator
			listIter.remove();

			//
			// !!!!!NOTE!!!!!!!
			// This node could belong to more than one
			// category. However, category rule evaluation is done 
			// based ONLY on the IP - therefore there is no need to
			// re-evaluate the validity against the rule
			//

			// change the nodeid on the RTCNode
			rtcN.setNodeID(newNodeId);

			// get the new key for this node
			rtcnKey.setNodeID(newNodeId);

			// add new node to the map
			m_map.put(rtcnKey, rtcN);

			// add to the nodeid map
			m_map.add(newNodeId, rtcN);

			// add to the nodeid/ip map
			m_map.add(newNodeId, ip,  rtcN);

			// remove old nodeid from the categories it belonged to
			// and the new nodeid
			Iterator catIter = rtcN.getCategories().listIterator();
			while(catIter.hasNext())
			{
				String catlabel = (String)catIter.next();

				RTCCategory rtcCat = (RTCCategory)m_categories.get(catlabel);
				rtcCat.deleteNode(oldNodeId);
				rtcCat.addNode(newNodeId);
			}
			

		}
	}

	/**
	 * Get the rtcnode with this nodeid/ip/svcname
	 *
	 * @param nodeid	the node id
	 * @param ip		the interface
	 * @param svcName	the service
	 */
	public synchronized RTCNode get(long nodeid, String ip, String svcName)
	{
		RTCNodeKey key = new RTCNodeKey(nodeid, ip, svcName);
		return (RTCNode)m_map.get(key);
	}

	/**
	 * Get the key
	 *
	 * @param key		the RTCNodeKey
	 */
	public synchronized RTCNode get(RTCNodeKey key)
	{
		return (RTCNode)m_map.get(key);
	}

	/**
	 * Get the value(uptime) for the category in the last 'rollingWindow' starting at current time
	 *
	 * @param catLabel 	the category to which the node should belong to
	 * @param curTime	the current time
	 * @param rollingWindow	the window for which value is to be calculated
	 *
	 * @return the value(uptime) for the category in the last 'rollingWindow' starting at current time
	 */
	public synchronized double getValue(String catLabel, long curTime, long rollingWindow)
	{
		return m_map.getValue(catLabel, curTime, rollingWindow);
	}

	/**
	 * Get the value(uptime) for the nodeid in the last 'rollingWindow' starting at current time in the context of the passed category
	 *
	 * @param nodeid 	the node for which value is to be calculated
	 * @param catLabel 	the category to which the node should belong to
	 * @param curTime	the current time
	 * @param rollingWindow	the window for which value is to be calculated
	 *
	 * @return the value(uptime) for the node in the last 'rollingWindow' starting at current time in the context of the passed category
	 */
	public synchronized double getValue(long nodeid, String catLabel, long curTime, long rollingWindow)
	{
		return m_map.getValue(nodeid, catLabel, curTime, rollingWindow);
	}

	/**
	 * Get the service count for the nodeid in the context of the passed category
	 *
	 * @param nodeid 	the node for which service count is to be calculated
	 * @param catLabel 	the category to which the node should belong to
	 *
	 * @return the service count for the nodeid in the context of the passed category
	 */
	public synchronized int getServiceCount(long nodeid, String catLabel)
	{
		return m_map.getServiceCount(nodeid, catLabel);
	}

	/**
	 * Get the service down count for the nodeid in the context of the passed category
	 *
	 * @param nodeid 	the node for which service down count is to be calculated
	 * @param catLabel 	the category to which the node should belong to
	 *
	 * @return the service down count for the nodeid in the context of the passed category
	 */
	public synchronized int getServiceDownCount(long nodeid, String catLabel)
	{
		return m_map.getServiceDownCount(nodeid, catLabel);
	}

	/**
	 * @return	the categories
	 */
	public synchronized Map getCategories()
	{
		return m_categories;
	}

	/**
	 * Return the service id for the name passed
	 *
	 * @param svcname	the service name whose service id is required
	 *
	 * @return the service id for the name passed, -1 if not found
	 */
	public synchronized long getServiceID(String svcname)
				throws SQLException
	{
		Long i = (Long)m_serviceTableMap.get(svcname);
		if ( i != null)
		{
			return i.longValue();
		}

		//
		// talk to the database and get the identifer
		//
		long id = -1;

		Connection dbConn = DatabaseConnectionFactory.getInstance().getConnection();
		PreparedStatement stmt = dbConn.prepareStatement(RTCConstants.SQL_DB_SVCNAME_TO_SVCID);
		stmt.setString(1, svcname);
		ResultSet rset = stmt.executeQuery();
		if (rset.next())
		{
			id = rset.getLong(1);
		}

		// Close db resources
		//
		try
		{
			rset.close();
			stmt.close(); 
			dbConn.close();
		}
		catch (Exception e)
		{
			ThreadCategory.getInstance(getClass()).warn("Exception closing JDBC resultset or statement or connection", e);
		}
		
		// take note of the new find
		if (id != -1)
			addServiceMapping(svcname, id);
		
		return id;
	}
	
	/**
	 * Add the svcname/svcid mapping to the servicetable map
	 */
	public synchronized void addServiceMapping(String svcname, long serviceid)
	{
		m_serviceTableMap.put(svcname, new Long(serviceid));
	}
}
