/**
 * Axelor Business Solutions
 *
 * Copyright (C) 2016 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.studio.service.builder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.xml.bind.JAXBException;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axelor.meta.db.MetaField;
import com.axelor.meta.db.MetaSelect;
import com.axelor.meta.db.MetaView;
import com.axelor.meta.loader.XMLViews;
import com.axelor.meta.schema.ObjectViews;
import com.axelor.meta.schema.actions.Action;
import com.axelor.meta.schema.actions.ActionRecord;
import com.axelor.meta.schema.actions.ActionRecord.RecordField;
import com.axelor.meta.schema.views.AbstractPanel;
import com.axelor.meta.schema.views.AbstractView;
import com.axelor.meta.schema.views.AbstractWidget;
import com.axelor.meta.schema.views.Button;
import com.axelor.meta.schema.views.FormView;
import com.axelor.meta.schema.views.Label;
import com.axelor.meta.schema.views.Menu;
import com.axelor.meta.schema.views.PanelEditor;
import com.axelor.meta.schema.views.Menu.Item;
import com.axelor.meta.schema.views.Panel;
import com.axelor.meta.schema.views.PanelField;
import com.axelor.meta.schema.views.PanelInclude;
import com.axelor.meta.schema.views.PanelMail;
import com.axelor.meta.schema.views.PanelMail.MailFollowers;
import com.axelor.meta.schema.views.PanelMail.MailMessages;
import com.axelor.meta.schema.views.PanelRelated;
import com.axelor.meta.schema.views.PanelTabs;
import com.axelor.meta.schema.views.Spacer;
import com.axelor.studio.db.ViewBuilder;
import com.axelor.studio.db.ViewItem;
import com.axelor.studio.db.ViewPanel;
import com.axelor.studio.service.ConfigurationService;
import com.axelor.studio.service.FilterService;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.inject.Inject;

/**
 * This class generate form view from ViewBuilder of type 'form'. It use
 * ViewPanels to generate panels of formView. Also generate onNew action from
 * default value given in view field.
 * 
 * @author axelor
 */
public class FormBuilderService {

	protected Logger log = LoggerFactory.getLogger(getClass());

	// List of RecordFields generated for default values.
	private List<RecordField> defaultFields;

	private Map<String, AbstractPanel> panelMap;

	private List<ActionRecord> actionRecords;

	private List<AbstractWidget> formViewItems;

	private ViewBuilder viewBuilder;

	@Inject
	private ConfigurationService configService;

	@Inject
	private FilterService filterService;
	
	private boolean autoCreate = false;

	/**
	 * Root method to access the service. It will generate FormView from
	 * ViewBuilder
	 * 
	 * @param viewBuilder
	 *            ViewBuilder record of type form.
	 * @return FormView
	 * @throws JAXBException
	 */
	public FormView getView(ViewBuilder viewBuilder, boolean autoCreate) throws JAXBException {
		
		this.autoCreate = autoCreate;
		log.debug("View builder toolbar: {}", viewBuilder.getToolbar().size());

		this.viewBuilder = viewBuilder;
		defaultFields = new ArrayList<RecordField>();
		panelMap = new HashMap<String, AbstractPanel>();
		actionRecords = new ArrayList<ActionRecord>();

		FormView formView = getFormView();
		processFormView(formView);

		processPanels(false);
		processPanels(true);

		if (viewBuilder.getAddStream()) {
			addStream();
		}

		if (!defaultFields.isEmpty()) {
			addOnNewAction(formView);
			log.debug("On new actions: {}", formView.getOnNew());
		}

		return formView;
	}

	/**
	 * Method update formView record by adding panels, toolbar buttons and onNew
	 * action into it.
	 * 
	 * @param formView
	 *            FormView generated from parent view.
	 */
	private void processFormView(FormView formView) {
		
		processToolBar(formView);
		
		processMenuBar(formView);

		String onSave = getUpdatedAction(viewBuilder.getOnSave(),
				formView.getOnSave());
		log.debug("OnSave actions final: {}", onSave);
		formView.setOnSave(onSave);

		String onNew = getUpdatedAction(viewBuilder.getOnNew(),
				formView.getOnNew());
		formView.setOnNew(onNew);
		
		String onLoad = getUpdatedAction(viewBuilder.getOnLoad(),
				formView.getOnLoad());
		formView.setOnLoad(onLoad);

		formView.setWidthSpec("large");
		log.debug("Process form view: {}", formView.getName());
		
		formViewItems = formView.getItems();
		
		if (formViewItems == null) {
			formViewItems = new ArrayList<AbstractWidget>();
		}
		else {
			addPanelInclude(formViewItems);
		}
		
//		updatePanelInclude(items.iterator(), 0);

		mapPanels(formViewItems.iterator(), null, 0);
		
		log.debug("Panel map keys: {}", panelMap.keySet());

		formView.setItems(formViewItems);
	}
	
	
	private void addPanelInclude(List<AbstractWidget> items) {
		
		List<AbstractWidget> copyItems = new ArrayList<AbstractWidget>();
		copyItems.addAll(items);
		
		int index = -1;

		for (AbstractWidget item : copyItems) {
			index++;

			if (item instanceof PanelInclude) {
				PanelInclude panel = (PanelInclude) item;
				FormView form = (FormView) panel.getView();
				if (form != null && form.getItems() != null) {
					List<AbstractWidget> subItems  = form.getItems();
					addPanelInclude(subItems);
					items.remove(index);
					items.addAll(index, subItems);
					index += subItems.size() - 1;
				}
			}
			else if (item instanceof PanelTabs) {
				PanelTabs tabs = (PanelTabs) item;
				addPanelInclude(tabs.getItems());
				items.remove(index);
				items.add(index, tabs);
			}
			else if (item instanceof Panel) {
				Panel panel = (Panel) item;
				addPanelInclude(panel.getItems());
				items.remove(index);
				items.add(index, panel);
			}
			
		}
		
	}
	
	/**
	 * Method generate final list of buttons to keep in formview. It removes old
	 * button and add new button as per ViewBuilder toolbar.
	 * 
	 * @param toolbar
	 *            List of buttons of Parent form view.
	 * @return List of button to keep in formView.
	 */
	private void processToolBar(FormView formView) {
		
		List<Button> toolbar = new ArrayList<Button>();

		for (ViewItem viewButton : viewBuilder.getToolbar()) {
			log.debug("Button: {} onClick:{}", viewButton.getName(),
					viewButton.getOnClick());
			String name = viewButton.getName();
			Button button = new Button();
			button.setName(name);
			toolbar.add(button);
			button.setTitle(viewButton.getTitle());
			if (viewButton.getColSpan() == 0) {
				button.setColSpan(null);
			}
			else {
				button.setColSpan(viewButton.getColSpan());
			}
			button.setShowIf(viewButton.getShowIf());
			button.setOnClick(viewButton.getOnClick());
			button.setReadonlyIf(viewButton.getReadonlyIf());
			button.setReadonly(viewButton.getReadonly());
			button.setHidden(viewButton.getHidden());
			button.setHideIf(viewButton.getHideIf());
		}

		if (!toolbar.isEmpty()) {
			formView.setToolbar(toolbar);
		}
	}
	
	
	private void processMenuBar(FormView formView) {
		
		List<ViewItem> menuItems = viewBuilder.getMenubar();
		
		List<Menu> menubar = new ArrayList<Menu>();
		
		for (ViewItem viewItem : menuItems) {
			
			Menu menu = new Menu();
			menu.setIcon(viewItem.getIcon());
			menu.setTitle(viewItem.getTitle());
			
			List<AbstractWidget> items = new ArrayList<AbstractWidget>();
			log.debug("Total items for menubar: {} is : {}", 
					menu.getTitle(), viewItem.getMenubarItems().size());
			for (ViewItem menuItem : viewItem.getMenubarItems()) {
				Item item = new Item();
				item.setAction(menuItem.getOnClick());
				item.setTitle(menuItem.getTitle());
				items.add(item);
			}
			
			menu.setItems(items);
			menubar.add(menu);
		}
		
		if (!menubar.isEmpty()) {
			formView.setMenubar(menubar);
		}
	}
	
	/**
	 * Method to update onSave string with new actions.
	 * 
	 * @param actions
	 *            String of actions separated by comma.
	 * @return String of updated actions with onSave.
	 */
	public static String getUpdatedAction(String oldAction, String action) {

		if (Strings.isNullOrEmpty(oldAction)) {
			return action;
		}
		if (Strings.isNullOrEmpty(action)) {
			return oldAction;
		}

		List<String> oldActions = new ArrayList<String>();
		oldActions.addAll(Arrays.asList(oldAction.split(",")));

		List<String> newActions = new ArrayList<String>();
		newActions.addAll(Arrays.asList(action.split(",")));
		newActions.removeAll(oldActions);

		oldActions.addAll(newActions);

		return Joiner.on(",").join(oldActions);
	}

	/**
	 * Method to get generated ActionRecords, after generating form view.
	 * 
	 * @return
	 */
	public List<ActionRecord> getActionRecords() {

		return actionRecords;
	}

	/**
	 * Method to get FormView from parent form view or create new if no parent
	 * form view.
	 * 
	 * @return FormView
	 * @throws JAXBException
	 *             Exception throws by xml parsing.
	 */
	private FormView getFormView() throws JAXBException {

		FormView formView = null;

		MetaView metaView = viewBuilder.getMetaView();
		
		String viewName = viewBuilder.getName();
		if (metaView == null) {
			formView = new FormView();
			formView.setName(viewName);
			formView.setTitle(viewBuilder.getTitle());
			formView.setModel(viewBuilder.getModel());
		}
		else {
			ObjectViews objectViews = XMLViews.fromXML(metaView.getXml());
			List<AbstractView> views = objectViews.getViews();
			if (!views.isEmpty()) {
				formView = (FormView) views.get(0);
			}
		}
		formView.setXmlId(configService.getModuleName() + "-" + viewName);

		return formView;
	}

	/**
	 * Method process viewPanels of viewBuilder to create new AbstractPanel and
	 * update AbstractPanel.
	 * 
	 * @param sideBar
	 *            boolean to check if panel is side panel or not
	 */
	private void processPanels(boolean sideBar) {

		List<ViewPanel> viewPanels;

		if (sideBar) {
			viewPanels = viewBuilder.getViewSidePanelList();
		} else {
			viewPanels = viewBuilder.getViewPanelList();
		}

		for (ViewPanel viewPanel : viewPanels) {

			String panelLevel = viewPanel.getPanelLevel();

			log.debug("Panel level to process: {}", panelLevel);
			AbstractPanel panel = null;

			if (panelMap.containsKey(panelLevel)) {
				if (viewPanel.getNewPanel() && viewBuilder.getAddOnly()) {
					adjustPanelLevel(panelLevel);
					panel = addNewPanel(panelLevel, viewPanel.getIsNotebook());
				}
				else {
					panel = panelMap.get(panelLevel);
				}
			} else {
				if (viewPanel.getNewPanel()) {
					adjustPanelLevel(panelLevel);
				}
				panel = addNewPanel(panelLevel, viewPanel.getIsNotebook());
			}

			if (sideBar) {
				panel.setSidebar(sideBar);
			}
			
			if (viewBuilder.getAddOnly()) {
				
				if (viewPanel.getNewPanel()) {
					panel = updatePanel(panel, viewPanel);
				}
			}
			else {
				panel = updatePanel(panel, viewPanel);
			}
			

//			panelMap.put(panelLevel, panel);

		}
	}
	
	private void adjustPanelLevel(String panelLevel) {
		
		String[] levels = panelLevel.split("\\.");
		
		String[] parentLevels = null;
		
		int index = levels.length - 1;
		
		Integer upLevel = Integer.parseInt(levels[index]) + 1;
		
		String levelUp = upLevel.toString();
		
		if (levels.length > 1) {
			parentLevels = Arrays.copyOf(levels, levels.length -1 );
		}
		
		List<String> updateLevels = new ArrayList<String>();
		
		for (String level : panelMap.keySet()) {
			String[] keyLevels = level.split("\\.");
			if (keyLevels.length < levels.length) {
				continue;
			}
			
			if (parentLevels == null) {
				if (Integer.parseInt(levels[0]) <= Integer.parseInt(keyLevels[0])) {
					updateLevels.add(level);
				}
			}
			else {
				String[] checkLevel = Arrays.copyOf(keyLevels, parentLevels.length);
				if (Arrays.equals(checkLevel, parentLevels) 
						&& Integer.parseInt(levels[index]) <= Integer.parseInt(keyLevels[index])) {
					updateLevels.add(level);
				}
			}
			
		}
		
		log.debug("Update levels: {}", updateLevels);
		Collections.sort(updateLevels);
		Collections.reverse(updateLevels);
		
		for (String level : updateLevels) {
			
			AbstractPanel panel = panelMap.get(level);
			
			panelMap.remove(level);
			
			String[] target = level.split("\\.");
			target[index] = levelUp;
			
			level = Joiner.on(".").join(target);
			
			panelMap.put(level, panel);
		}
		
		
	}

	/**
	 * Method to create new Panel with specified panel level
	 * 
	 * @param panelLevel
	 *            Level of panel in formView.
	 * @param isNotebook
	 *            Boolean to check if panel is notebook.
	 * @return AbstractPanel
	 */
	private AbstractPanel addNewPanel(String panelLevel, Boolean isNotebook) {
		
		AbstractPanel abstractPanel = new Panel();
		if (isNotebook) {
			abstractPanel = new PanelTabs();
		}
		abstractPanel.setColSpan(12);

		if (!panelLevel.contains(".")) {
			Integer panelIndex = Integer.parseInt(panelLevel);
			if (panelIndex < formViewItems.size()) {
				formViewItems.add(panelIndex + 1, abstractPanel);
			} else {
				formViewItems.add(abstractPanel);
			}
			panelMap.put(panelLevel, abstractPanel);

			return abstractPanel;
		}

		Integer lastIndex = panelLevel.lastIndexOf(".");
		String parentLevel = panelLevel.substring(0, lastIndex);
		if (!panelMap.containsKey(parentLevel)) {
			log.debug("No parent panel level: {}", panelLevel);
			return abstractPanel;
		}

		List<AbstractWidget> panelItems = null;
		
		if (panelMap.get(parentLevel) instanceof Panel) {
			Panel panel = (Panel) panelMap.get(parentLevel);
			panelItems = panel.getItems();
			if (panelItems == null) {
				panelItems = new ArrayList<AbstractWidget>();
			}
			panelItems.add(abstractPanel);
			panel.setItems(panelItems);
			panelMap.put(parentLevel, panel);
			panelMap.put(panelLevel, abstractPanel);
		} else if (panelMap.get(parentLevel) instanceof PanelTabs) {
			PanelTabs panelTabs = (PanelTabs) panelMap.get(parentLevel);
			panelItems = panelTabs.getItems();
			if (panelItems == null) {
				panelItems = new ArrayList<AbstractWidget>();
			}
			panelItems.add(abstractPanel);
			panelTabs.setItems(panelItems);
			panelMap.put(parentLevel, panelTabs);
			panelMap.put(panelLevel, abstractPanel);
		}

		return abstractPanel;

	}

	/**
	 * Method to update panelMap with generated paneLevel as key and Panel as
	 * value.
	 * 
	 * @param widgetIterator
	 *            Iterator of formView items.
	 * @param level
	 *            Parent panel level
	 * @param levelCounter
	 *            Current level counter.
	 */
	private void mapPanels(Iterator<AbstractWidget> widgetIterator,
			String level, Integer levelCounter) {

		if (widgetIterator.hasNext()) {

			AbstractWidget widget = widgetIterator.next();
			String currentLevel = levelCounter.toString();
			if (level != null) {
				currentLevel = level + "." + currentLevel;
			}

			if (widget instanceof Panel) {
				Panel panel = (Panel) widget;
				panelMap.put(currentLevel, panel);
				mapPanels(panel.getItems().iterator(), currentLevel, 0);
				levelCounter += 1;
			} else if (widget instanceof PanelTabs) {
				PanelTabs panelTabs = (PanelTabs) widget;
				panelMap.put(currentLevel, panelTabs);
				mapPanels(panelTabs.getItems().iterator(), currentLevel, 0);
				levelCounter += 1;
			}

			mapPanels(widgetIterator, level, levelCounter);
		}

	}
	
	/**
	 * Method to update AbstractPanel from ViewPanel of formView .
	 * 
	 * @param abstractPanel
	 *            Destination panel to be updated.
	 * @param viewPanel
	 *            Source panel.
	 */
	private AbstractPanel updatePanel(AbstractPanel abstractPanel,
			ViewPanel viewPanel) {

		// if(!viewPanel.getNoTitle()){
		abstractPanel.setTitle(viewPanel.getTitle());
		// }
		
		String colspan = viewPanel.getColspan();
		if (colspan != null && StringUtils.isNumeric(colspan)) {
			abstractPanel.setColSpan(Integer.parseInt(colspan));
		}

		abstractPanel.setName(viewPanel.getName());

		if (abstractPanel instanceof Panel) {
			List<AbstractWidget> panelItems = new ArrayList<AbstractWidget>();
			Panel panel = (Panel) abstractPanel;
			if (panel.getItems() != null) {
				panelItems = panel.getItems();
			}

			List<ViewItem> itemList = viewPanel.getViewItemList();
			List<AbstractWidget> items = processItems(itemList, panel);

			if (viewPanel.getPlace() == 0) {
				panelItems.addAll(0, items);
			} else {
				panelItems.addAll(items);
			}
			
			if (viewPanel.getReadonly()) {
				panel.setReadonly(true);
			}
			panel.setReadonlyIf(viewPanel.getReadonlyIf());
			
			if (viewPanel.getHidden()) {
				panel.setHidden(true);
			}
			panel.setHideIf(viewPanel.getHideIf());
			
			panel.setItems(panelItems);

			return panel;
		}

		return abstractPanel;
	}
	
	public List<AbstractWidget> processItems(List<ViewItem> viewItems, Panel panel) {
		
		List<AbstractWidget> items = new ArrayList<AbstractWidget>();

		for (ViewItem viewItem : viewItems) {

			Integer type = viewItem.getTypeSelect();

			switch (type) {
			case 0:
				String fieldType = viewItem.getFieldType();
				if (fieldType != null && "one-to-many,many-to-many".contains(fieldType)) {
					if (!viewItem.getNestedViewItems().isEmpty()) {
						items.add(createField(viewItem, true));
					}
					else {
						setPanelRelated(viewItem, items);
					}
					
				} else {
					items.add(createField(viewItem, false));
				}
				checkDefaultValues(fieldType, viewItem);
				break;
			case 1:
				if (viewItem.getPanelTop()) {
					setMenuItem(viewItem, panel);
				} else {
					setButton(viewItem, items);
				}
				break;
			case 2:
				setLabel(viewItem, items);
				break;
			case 3:
				setSpacer(viewItem, items);
			}

		}
		
		return items;
	}

	/**
	 * Method check if default value is there for ViewField. If defaultValue is
	 * there it will update defaultFields list that will be used in onNew action
	 * generation.
	 * 
	 * @param fieldType
	 *            Type of field
	 * @param fieldName
	 *            Name of field
	 * @param viewField
	 *            ViewField to check for default vale.
	 */
	private void checkDefaultValues(String fieldType, ViewItem viewItem) {

		String defaultVal = viewItem.getDefaultValue();
		String name = viewItem.getName();

		if (defaultVal != null) {

			// if(fieldType.equals("string")){
			// defaultVal = "'" + defaultVal + "'";
			// }

			RecordField recordField = new RecordField();
			recordField.setName(name);
			recordField.setExpression("eval:"
					+ filterService.getTagValue(defaultVal, false));

			defaultFields.add(recordField);

		}

	}

	/**
	 * Method update PanelItems list with new item created from viewField.
	 * 
	 * @param fieldName
	 *            Name of field
	 * @param viewField
	 *            Source field.
	 * @param panelItems
	 *            Destination list to update.
	 */
	private PanelField createField(ViewItem viewItem, boolean addEditor) {

		PanelField field = new PanelField();
		field.setName(viewItem.getName());
		field.setOnChange(viewItem.getOnChange());
		field.setDomain(viewItem.getDomainCondition());
		field.setReadonlyIf(viewItem.getReadonlyIf());
		field.setHideIf(viewItem.getHideIf());
		field.setRequiredIf(viewItem.getRequiredIf());
		field.setColSpan(null);
		String selectWidget = viewItem.getWidget();
		String widget = null;
		MetaField metaField = viewItem.getMetaField();
		
		if (addEditor) {
			field.setEditor(createEditor(viewItem));
			field.setColSpan(12);
		}
		
		if (viewItem.getHidden()) {
			field.setHidden(true);
		} else {
			field.setHidden(null);
		}

		if (viewItem.getRequired()) {
			field.setRequired(true);
		} else {
			field.setRequired(null);
		}

		if (viewItem.getReadonly()) {
			field.setReadonly(true);
		} else {
			field.setReadonly(null);
		}

		if (viewItem.getProgressBar()) {
			widget = "SelectProgress";
		} else if (viewItem.getHtmlWidget()) {
			field.setColSpan(12);
			widget = "html";
		} else if (selectWidget != null && !selectWidget.equals("normal")) {
			widget = selectWidget;
		}

		if (metaField != null) {
			if (metaField.getIsDuration()) {
				widget = "duration";
			} else if (metaField.getIsUrl()) {
				widget = "url";
			} else if (metaField.getLarge()) {
				field.setColSpan(12);
			}
			String relationship = metaField.getRelationship();
			if (autoCreate 
					&& relationship != null 
					&& "ManyToOne,ManyToMany".contains(relationship)) {
				field.setCanNew("true");
			}
		}
		
		if (viewItem.getColSpan() > 0) {
			field.setColSpan(viewItem.getColSpan());
		}

		field.setWidget(widget);

		MetaSelect metaSelect = viewItem.getMetaSelect();
		if (metaSelect != null) {
			field.setSelection(metaSelect.getName());
		} else {
			field.setSelection(null);
		}
		
		return field;

	}

	private void setButton(ViewItem viewItem, List<AbstractWidget> panelItems) {

		Button button = new Button();
		button.setName(viewItem.getName());
		button.setColSpan(viewItem.getColSpan());
		button.setTitle(viewItem.getTitle());
		button.setOnClick(viewItem.getOnClick());
		button.setPrompt(viewItem.getPromptMsg());

		panelItems.add(button);

	}

	private Panel setMenuItem(ViewItem viewItem, Panel panel) {

		Menu menu = panel.getMenu();
		if (menu == null) {
			menu = new Menu();
		}

		List<AbstractWidget> items = menu.getItems();
		if (items == null) {
			items = new ArrayList<AbstractWidget>();
		}

		Integer oldIndex = null;
		for (AbstractWidget menuItem : items) {
			Item item = (Item) menuItem;
			String name = item.getName();
			if (name != null && name.equals(viewItem.getName())) {
				oldIndex = items.indexOf(item);
				break;
			}
		}

		Item item = new Item();
		// item.setName(viewItem.getName());
		item.setTitle(viewItem.getTitle());
		String onClick = viewItem.getOnClick();
		if (Strings.isNullOrEmpty(onClick)) {
			onClick = "";
		}
		item.setAction(onClick);

		if (oldIndex != null) {
			items.remove(oldIndex);
			items.add(oldIndex, item);
		} else {
			items.add(item);
		}

		menu.setItems(items);
		panel.setMenu(menu);

		return panel;
	}

	/**
	 * Method to add panel related for o2m/m2m types of relational field. It
	 * will add PanelRelated object created from viewField into list of
	 * panelItems of parent panel.
	 * 
	 * @param fieldName
	 *            Name of relational field.
	 * @param viewField
	 *            ViewField of relational type.
	 * @param panelItems
	 *            List of items of parent panel.
	 */
	private void setPanelRelated(ViewItem viewItem,
			List<AbstractWidget> panelItems) {

		PanelRelated panelRelated = new PanelRelated();
		panelRelated.setName(viewItem.getName());
		Integer colspan = viewItem.getColSpan();
		if (colspan != null && colspan != 0) {
			panelRelated.setColSpan(colspan);
		}
		else {
			panelRelated.setColSpan(12);
		}
		
		panelRelated.setOnChange(viewItem.getOnChange());
		panelRelated.setDomain(viewItem.getDomainCondition());
		panelRelated.setReadonlyIf(viewItem.getReadonlyIf());
		panelRelated.setHideIf(viewItem.getHideIf());

		if (viewItem.getReadonly()) {
			panelRelated.setReadonly(true);
		} else {
			panelRelated.setReadonly(null);
		}

		if (viewItem.getHidden()) {
			panelRelated.setHidden(true);
		} else {
			panelRelated.setHidden(null);
		}

		panelItems.add(panelRelated);

	}

	/**
	 * Method generate ActionRecord from defaultFields and using form view
	 * details. Also it set onNew of formView.
	 * 
	 * @param formView
	 *            Updated form view with onNew action.
	 */
	private void addOnNewAction(FormView formView) {

		String model = viewBuilder.getModel();
		String actionName = "custom-" + formView.getName() + "-default";
		Action action = XMLViews.findAction(actionName);
		ActionRecord actionRecord;

		if (action instanceof ActionRecord) {
			actionRecord = (ActionRecord) action;
			removeOldActionFields(actionRecord);
		} else {
			actionRecord = new ActionRecord();
			actionRecord.setName(actionName);
			actionRecord.setModel(model);
		}

		actionRecord.setFields(defaultFields);

		String onNew = getUpdatedAction(formView.getOnNew(), actionName);
		formView.setOnNew(onNew);

		actionRecords.add(actionRecord);
	}

	/**
	 * Method remove old defaultFields and update new default field of
	 * ActionRecord.
	 * 
	 * @param actionRecord
	 *            ActionRecord to update.
	 */

	private void removeOldActionFields(ActionRecord actionRecord) {

		List<RecordField> oldFields = actionRecord.getFields();

		for (RecordField recordField : defaultFields) {

			Iterator<RecordField> toRemove = oldFields.iterator();

			while (toRemove.hasNext()) {

				RecordField oldField = toRemove.next();

				if (oldField.getName().equals(recordField.getName())) {
					toRemove.remove();
					break;
				}

			}
		}

		defaultFields.addAll(oldFields);
	}

	private void addStream() {

		PanelMail panelMail = new PanelMail();

		List<AbstractWidget> items = new ArrayList<AbstractWidget>();

		MailMessages mailMessages = new MailMessages();
		mailMessages.setLimit(4);
		items.add(mailMessages);

		MailFollowers mailFollowers = new MailFollowers();
		items.add(mailFollowers);

		panelMail.setItems(items);

		formViewItems.add(panelMail);

	}
	
	private void setLabel(ViewItem item, List<AbstractWidget> items){
		
		Label label = new Label();
		label.setTitle(item.getTitle());
		label.setName(item.getName());
		label.setHideIf(item.getHideIf());
		
		items.add(label);
	}
	
	
	private void setSpacer(ViewItem item, List<AbstractWidget> items){
		
		Spacer spacer = new Spacer();
		spacer.setColSpan(item.getColSpan());
		
		items.add(spacer);
	}

	private PanelEditor createEditor(ViewItem viewItem) {
		
		List<AbstractWidget> items = processItems(viewItem.getNestedViewItems(), null);
		
		if (!items.isEmpty()) {
			PanelEditor editor = new PanelEditor();
			editor.setItems(items);
			return editor;
		}
		
		return null;
	}

}
