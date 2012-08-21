/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2009-2011 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2011 The OpenNMS Group, Inc.
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

package org.opennms.features.topology.plugins.topo.vmware.internal;

import javax.xml.bind.annotation.XmlID;
import javax.xml.bind.annotation.XmlIDREF;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement(name = "edge")
public class VmwareEdge {
    private String m_id;
    private VmwareVertex m_source;
    private VmwareVertex m_target;

    public VmwareEdge() {
    }

    public VmwareEdge(String id, VmwareVertex source, VmwareVertex target) {
        m_id = id;
        m_source = source;
        m_target = target;

        m_source.addEdge(this);
        m_target.addEdge(this);
    }

    @XmlID
    public String getId() {
        return m_id;
    }

    public void setId(String id) {
        m_id = id;
    }

    @XmlIDREF
    public VmwareVertex getSource() {
        return m_source;
    }

    public void setSource(VmwareVertex source) {
        m_source = source;
        m_source.addEdge(this);
    }

    @XmlIDREF
    public VmwareVertex getTarget() {
        return m_target;
    }

    public void setTarget(VmwareVertex target) {
        m_target = target;
        m_target.addEdge(this);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((m_id == null) ? 0 : m_id.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        VmwareEdge other = (VmwareEdge) obj;
        if (m_id == null) {
            if (other.m_id != null)
                return false;
        } else if (!m_id.equals(other.m_id))
            return false;
        return true;
    }
}