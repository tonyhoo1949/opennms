/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2006-2011 The OpenNMS Group, Inc.
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
package org.opennms.features.vaadin.mibcompiler;

import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.TextField;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Window;
import com.vaadin.ui.themes.Runo;

/**
 * The Class Event Generator Window.
 * 
 * @author <a href="mailto:agalue@opennms.org">Alejandro Galue</a> 
 */
@SuppressWarnings("serial")
public abstract class EventGenerationWindow extends Window implements Button.ClickListener {

    /** The Event UEI base. */
    private final TextField ueiBase;

    /** The cancel. */
    private final Button okButton;

    /**
     * Instantiates a new Event Generator window.
     *
     */
    public EventGenerationWindow() {
        setCaption("Edit MIB");
        setModal(true);
        setWidth("400px");
        setHeight("150px");
        setResizable(false);
        setClosable(false);
        addStyleName(Runo.WINDOW_DIALOG);

        ueiBase = new TextField("UEI Base");
        ueiBase.setNullSettingAllowed(false);
        ueiBase.setWriteThrough(false);
        ueiBase.setWidth("100%");

        okButton = new Button("Continue");
        okButton.addListener(this);

        addComponent(ueiBase);
        addComponent(okButton);

        ((VerticalLayout) getContent()).setComponentAlignment(okButton, Alignment.BOTTOM_RIGHT);
    }

    /* (non-Javadoc)
     * @see com.vaadin.ui.Button.ClickListener#buttonClick(com.vaadin.ui.Button.ClickEvent)
     */
    public void buttonClick(Button.ClickEvent event) {
        if (ueiBase.getValue() != null) {
            getApplication().getMainWindow().removeWindow(this);
            changeUeiHandler((String)ueiBase.getValue());
        }
    }

    /**
     * Change UEI handler.
     *
     * @param ueiBase the UEI base
     */
    public abstract void changeUeiHandler(String ueiBase);

}
