/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2011-2012 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2012 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.features.gwt.snmpselect.list.client.view;

import com.google.gwt.core.client.JavaScriptObject;

public class SnmpCellListItem extends JavaScriptObject {
    
    protected SnmpCellListItem() {
        
    }
    
    public final native String getIfIndex()/*-{
        return this.ifIndex === null? null : ('' + this.ifIndex);
    }-*/;

    public final native String getSnmpType() /*-{
        return this.ifType === null? null : ('' + this.ifType);
    }-*/;

    public final native String getIfDescr() /*-{
        return this.ifDescr;
    }-*/;

    public final native String getIfName() /*-{
        return this.ifName;
    }-*/;

    public final native String getIfAlias() /*-{
        return this.ifAlias;
    }-*/;

    public final native String getCollectFlag() /*-{
        return this.collectFlag;
    }-*/;
    
    public final native void setCollectFlag(final String flag) /*-{
        this.collectFlag = flag;
    }-*/;

    public final native int getIfAdminStatus() /*-{
        return parseInt(this.ifAdminStatus);
    }-*/;

    public final native int getIfOperStatus() /*-{
        return parseInt(this.ifOperStatus);
    }-*/;

    public final native int getId() /*-{
        return parseInt(this.id);
    }-*/;
         
}
