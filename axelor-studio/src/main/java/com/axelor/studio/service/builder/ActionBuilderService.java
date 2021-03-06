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

import groovy.lang.GroovyShell;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.xml.bind.JAXBException;

import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axelor.apps.message.db.Template;
import com.axelor.apps.message.db.repo.TemplateRepository;
import com.axelor.apps.message.service.TemplateMessageService;
import com.axelor.db.JpaSequence;
import com.axelor.exception.AxelorException;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.db.MetaField;
import com.axelor.meta.db.MetaModel;
import com.axelor.meta.db.repo.MetaFieldRepository;
import com.axelor.meta.schema.actions.Action;
import com.axelor.meta.schema.actions.ActionMethod;
import com.axelor.meta.schema.actions.ActionMethod.Call;
import com.axelor.meta.schema.actions.ActionValidate;
import com.axelor.meta.schema.actions.ActionValidate.Alert;
import com.axelor.meta.schema.actions.ActionValidate.Error;
import com.axelor.meta.schema.actions.ActionValidate.Info;
import com.axelor.meta.schema.actions.ActionValidate.Notify;
import com.axelor.meta.schema.actions.ActionValidate.Validator;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.meta.schema.actions.ActionRecord.RecordField;
import com.axelor.meta.schema.actions.ActionRecord;
import com.axelor.meta.schema.actions.ActionView.ActionViewBuilder;
import com.axelor.script.ScriptBindings;
import com.axelor.studio.db.ActionBuilder;
import com.axelor.studio.db.ActionBuilderLine;
import com.axelor.studio.db.Filter;
import com.axelor.studio.db.ReportBuilder;
import com.axelor.studio.db.ViewBuilder;
import com.axelor.studio.db.repo.ActionBuilderRepository;
import com.axelor.studio.service.FilterService;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

public class ActionBuilderService {

	private final Logger log = LoggerFactory.getLogger(getClass());

	private Map<String, List<Action>> modelActionMap;

	@Inject
	private ActionBuilderRepository actionBuilderRepo;

	@Inject
	private ViewBuilderService viewBuilderService;

	@Inject
	private MetaFieldRepository metaFieldRepo;

	@Inject
	private FilterService filterService;

	private ScriptBindings scriptBindings;
	
	private String errors;

	public String build(File viewDir, boolean updateMeta) throws IOException,
			JAXBException {
		
		errors = null;
		String query = "self.edited = true";
		if (!updateMeta) {
			query += " OR self.recorded = false";
		}
		List<ActionBuilder> actionBuilders = actionBuilderRepo.all()
				.filter(query).fetch();

		log.debug("Total actions to process: {}", actionBuilders.size());

		if (actionBuilders.isEmpty()) {
			return errors;
		}
		scriptBindings = new ScriptBindings(new HashMap<String, Object>());
		modelActionMap = new HashMap<String, List<Action>>();

		processActionBuilder(actionBuilders.iterator());

		for (String model : modelActionMap.keySet()) {
			List<Action> actions = modelActionMap.get(model);
			log.debug("Model : {}", model);
			log.debug("Actions: {}", actions.size());
			viewBuilderService.generateMetaAction(actions);
			if (!updateMeta) {
				String[] models = model.split("\\.");
				viewBuilderService.writeView(viewDir,
						models[models.length - 1], null, actions);
			}
		}

		updateEdited(actionBuilders, updateMeta);
		
		return errors;
	}

	@Transactional
	public void updateEdited(List<ActionBuilder> actionBuilders,
			boolean updateMeta) {

		for (ActionBuilder actionBuilder : actionBuilders) {
			actionBuilder.setEdited(false);
			if (!updateMeta) {
				actionBuilder.setRecorded(true);
			}
			actionBuilderRepo.save(actionBuilder);
		}
	}

	private void processActionBuilder(Iterator<ActionBuilder> actionIter) {

		if (!actionIter.hasNext()) {
			return;
		}

		ActionBuilder actionBuilder = actionIter.next();
		log.debug("Processing action: {}, type: {}", actionBuilder.getName(),
				actionBuilder.getTypeSelect());
		MetaModel model = getModel(actionBuilder);
		log.debug("Action model: {}", model);
		Integer actionType = actionBuilder.getTypeSelect();

		Action action = null;

		switch (actionType) {
		case 0:
			action = createActionRecord(model, actionBuilder, true);
			break;
		case 1:
			action = createActionRecord(model, actionBuilder, false);
			break;
		case 2:
			action = createActionView(model, actionBuilder);
			break;
		case 3:
			action = createActionReport(model, actionBuilder);
			break;
		case 4:
			action = createActionEmail(model, actionBuilder);
			break;
		case 5:
			action = createActionValidation(actionBuilder);
			break;
		default:
			processActionBuilder(actionIter);
		}

		String modelName = "Dashboard";
		if (model != null) {
			modelName = model.getFullName();
		}
		
		updateModelActionMap(modelName, action);
		processActionBuilder(actionIter);
	}

	private void updateModelActionMap(String model, Action action) {

		if (action != null) {
			if (!modelActionMap.containsKey(model)) {
				modelActionMap.put(model, new ArrayList<Action>());
			}

			modelActionMap.get(model).add(action);
		}
	}

	private Action createActionRecord(MetaModel model,
			ActionBuilder actionBuilder, boolean create) {

		String name = actionBuilder.getName();
		String modelName = model.getFullName();
		ActionRecord actionRecord = new ActionRecord();
		actionRecord.setModel(modelName);
		actionRecord.setName(name);
		String assign = name + "-assign";

		if (create) {
			actionRecord.setSaveIf("true");
		}

		List<RecordField> fields = createFields(actionBuilder);
		if (fields.isEmpty()) {
			return null;
		}

		actionRecord.setFields(fields);

		modelName = actionBuilder.getMetaModel().getFullName();

		MetaField loopOnField = actionBuilder.getLoopOnField();
		if (loopOnField != null) {
			String expr = createLoopOnExpression(actionBuilder, assign);
			createParentAction(modelName, name, loopOnField.getName(), expr,
					create);
			actionRecord.setName(assign);
			return actionRecord;
		}

		MetaField targetField = actionBuilder.getTargetField();

		if (targetField != null) {
			String expr = "action:" + assign;
			actionRecord.setName(assign);
			createParentAction(modelName, name, targetField.getName(), expr,
					false);
		}

		return actionRecord;
	}

	private MetaModel getModel(ActionBuilder actionBuilder) {

		MetaModel metaModel = actionBuilder.getMetaModel();

		if (actionBuilder.getTypeSelect() < 2) {
			MetaModel targetModel = actionBuilder.getTargetModel();
			if (targetModel != null) {
				return targetModel;
			}
		}

		return metaModel;
	}

	private List<RecordField> createFields(ActionBuilder actionBuilder) {

		List<RecordField> fields = new ArrayList<ActionRecord.RecordField>();
		String builderName = actionBuilder.getName();
		for (ActionBuilderLine line : actionBuilder.getLines()) {
			RecordField field = new RecordField();
			String[] target = line.getTargetField().split("\\.");
			field.setName(target[0]);
			String condition = line.getConditionText();
			if (!Strings.isNullOrEmpty(condition)
					&& !checkGroovy(condition, true)) {
				addError(builderName, condition, true);
				continue;
			}
			field.setCondition(condition);
			String value = line.getValue();
			MetaField metaField = line.getMetaField();
			if (metaField == null) {
				log.debug("No meta field found for target: {}",
						line.getTargetField());
				continue;
			}
			String expr = getRecordExpression(target, line.getMetaField(),
					value, line.getFilter());
			if (!Strings.isNullOrEmpty(expr)
					&& !checkGroovy(expr, true)) {
				addError(builderName, value, false);
				continue;
			}
			log.debug("Expr: {}", expr);
			if(expr != null && (expr.startsWith("call:") 
					|| expr.startsWith("action:") 
					|| expr.startsWith("eval:"))){
				field.setExpression(expr);
			}
			else{
				field.setExpression("eval:" + expr);
			}
			fields.add(field);
		}

		return fields;

	}

	private String getRecordExpression(String[] target, MetaField metaField,
			String value, String filter) {

		String typeName = metaField.getTypeName();
		log.debug("Target: {}, TypeName: {}", target[0], typeName);

		String relationship = metaField.getRelationship();
		if (relationship != null && target.length > 1) {
			return getRelationalValue(target, value, typeName, filter);
		}

		if (value == null) {
			return value;
		}

		if (value.startsWith("$$")) {
			return "_parent." + value.substring(2);
		}

		if (value.contains("sum(")) {
			return getSumValue(value, filter);
		}

		return value;

	}

	private String getRelationalValue(String[] target, String value,
			String typeName, String filter) {

		target = Arrays.copyOfRange(target, 1, target.length);

		if (value == null && filter != null) {
			return "__repo__.of(" + typeName + ").all().filter(&quot;" + filter
					+ "&quot;).fetchOne()";
		}

		if (value == null) {
			return null;
		}

		if (value.startsWith("$$")) {
			value = "_parent." + value.substring(2);
		} else if (!scriptBindings.containsKey(value)) {
			String type = getTypeName(target, typeName);
			if (type == null) {
				return null;
			}
		}

		String field = Joiner.on(".").join(target);

		return "__repo__.of(" + typeName + ").all().filter(&quot;self." + field
				+ " = ?&quot;," + value + ").fetchOne()";
	}

	private String getSumValue(String value, String filter) {

		Integer sumInd = value.indexOf("sum(");
		String expr = value.substring(sumInd + 4);
		
		if (!expr.isEmpty() && expr.contains(")")) {
			expr = expr.substring(0, expr.indexOf(")"));
			String[] val = expr.split(";");
			if (val.length < 2) {
				return null;
			}
			val[1] = val[1].replace("$$", "_parent.").replace("$", "it.");
			if(Character.isUpperCase(val[0].charAt(0))){
				val[0] = getModelExpression(val, filter);
			}
			else if (filter != null) {
				val[0] = val[0] + ".findAll{it->"
						+ filter.replace("$$", "_parent.").replace("$", "it.")
						+ "}";
			}
			val[1] = val[0] + ".collect{it->" + val[1] + "}.sum()";
			value = value.replace("sum(" + expr + ")", val[1]);
			return value;
		} else {
			return null;
		}

	}
	
	private String getModelExpression(String[] val, String filter) {
		
		String expr = "__repo__.of(" + val[0] + ").all()";
		
		if (filter != null) {
			expr += ".filter(&quot;" + filter.replace("$", "self.") + "&quot;)";
		}
		
		return expr + ".fetch()";
				
	}

	private String getTypeName(String[] target, String typeName) {

		MetaField metaField = metaFieldRepo
				.all()
				.filter("self.name = ?1 and self.metaModel.name = ?2",
						target[0], typeName).fetchOne();

		if (metaField != null) {
			if (metaField.getRelationship() != null && target.length > 1) {
				target = Arrays.copyOfRange(target, 1, target.length);
				return getTypeName(target, metaField.getTypeName());
			}
			return metaField.getTypeName();
		}

		log.debug("No field found Model: {}, Field: {}", typeName, target[0]);

		return null;
	}

	private void createParentAction(String model, String name, String field,
			String expr, boolean dummy) {

		ActionRecord actionRecord = new ActionRecord();
		actionRecord.setName(name);
		actionRecord.setModel(model);

		List<RecordField> fields = new ArrayList<ActionRecord.RecordField>();
		RecordField recordField = new RecordField();
		recordField.setExpression(expr);
		if (dummy) {
			field = "$dummy" + field;
		}
		recordField.setName(field);
		fields.add(recordField);
		actionRecord.setFields(fields);

		updateModelActionMap(model, actionRecord);
	}

	private Action createActionView(MetaModel model, ActionBuilder actionBuilder) {

		ViewBuilder viewBuilder = actionBuilder.getViewBuilder();
		String viewName = viewBuilder.getName();
		String viewType = viewBuilder.getViewType();
		String title = viewBuilder.getTitle();

		ActionViewBuilder builder = ActionView.define(title);
		builder.add(viewType, viewName);
		builder.name(actionBuilder.getName());
		builder.param("popup", actionBuilder.getPopup().toString());

		if (!viewType.equals("dashboard")) {
			viewType = viewType.equals("form") ? "grid" : "form";
			builder.add(viewType);
			if (model != null) {
				builder.model(model.getFullName());
			}
			updateDomainContext(builder, actionBuilder);
		}

		return builder.get();
	}

	private String createLoopOnExpression(ActionBuilder actionBuilder,
			String action) {

		MetaField loopOnField = actionBuilder.getLoopOnField();
		String firstGroupBy = actionBuilder.getFirstGroupBy();
		String secondGroupBy = actionBuilder.getSecondGroupBy();
		StringBuilder builder = new StringBuilder("eval:"
				+ loopOnField.getName());

		String filter = filterService.getGroovyFilters(
				actionBuilder.getFilters(), "it");
		log.debug("Loopon filter: {}", filter);
		if (filter != null) {
			// builder.append(".findAll{it->" + filter.replace("$$",
			// "_parent.").replace("$","it.") + "}");
			builder.append(".findAll{it->" + filter + "}");
		}

		if (firstGroupBy != null && secondGroupBy != null) {
			builder = updateMultiGroup(actionBuilder, builder, firstGroupBy,
					secondGroupBy);
		} else if (firstGroupBy != null) {
			builder = updateGroup(actionBuilder, builder, firstGroupBy);
		}

		builder.append("?.collect{__config__.action.execute('" + action
				+ "', it, __this__)}");

		return builder.toString();

	}

	private StringBuilder updateMultiGroup(ActionBuilder actionBuilder,
			StringBuilder builder, String firstGroupBy, String secondGroupBy) {

		builder.append(".groupBy({it." + firstGroupBy + "},{it."
				+ secondGroupBy + "})");
		builder.append(".collect{k,v -> v.collect{x,y ->[");
		builder.append("'" + firstGroupBy + "' : y[0]." + firstGroupBy);
		builder.append(",'" + secondGroupBy + "' : y[0]." + secondGroupBy);
		for (ActionBuilderLine line : actionBuilder.getLines()) {
			String value = line.getValue();
			if (value != null && !value.startsWith("$$")) {
				if (!firstGroupBy.equals(value) && !secondGroupBy.equals(value)) {
					builder.append(",'" + value + "' : y.collect{t->t." + value
							+ "}.sum()");
				}
			}
		}
		builder.append("]}}.flatten()");

		return builder;

	}

	private StringBuilder updateGroup(ActionBuilder actionBuilder,
			StringBuilder builder, String firstGroupBy) {

		builder.append(".groupBy{it." + firstGroupBy + "}");
		builder.append(".collect{k,v->v}.collect{it->[");
		builder.append("'" + firstGroupBy + "' : it[0]." + firstGroupBy);
		for (ActionBuilderLine line : actionBuilder.getLines()) {
			String value = line.getValue();
			if (value != null && !value.startsWith("$$")) {
				if (!firstGroupBy.equals(value)) {
					builder.append(",'" + value + "' : it.collect{t->t."
							+ value + "}.sum()");
				}
			}
		}
		builder.append("]}");

		return builder;
	}

	private Action createActionReport(MetaModel model,
			ActionBuilder actionBuilder) {

		ActionMethod method = new ActionMethod();
		method.setModel(model.getFullName());
		method.setName(actionBuilder.getName());

		Call call = new Call();
		call.setController("com.axelor.studio.web.ReportBuilderController");

		List<String> builders = new ArrayList<String>();
		for (ReportBuilder builder : actionBuilder.getReportBuilderSet()) {
			builders.add(builder.getId().toString());
		}

		String builderIds = Joiner.on(",").join(builders);

		call.setMethod("print('" + builderIds + "', id, false)");
		method.setCall(call);

		return method;
	}

	private void updateDomainContext(ActionViewBuilder builder,
			ActionBuilder actionBuilder) {

		String domain = null;
		int counter = 1;

		for (Filter filter : actionBuilder.getFilters()) {

			String value = filter.getValue();
			String origVal = value;
			String param = null;
			filter.setIsParameter(false);
			if (value != null) {
				filter.setIsParameter(true);
				param = "_param" + counter;
				if (value.startsWith("$$")) {
					value = value.substring(2);
				}
				filter.setValue(value);
				builder.context(param, "eval:" + value);
			}

			domain = addCondition(domain, filter, param);

			filter.setValue(origVal);

		}

		builder.domain(domain);

	}

	private String addCondition(String domain, Filter filter, String param) {

		MetaField field = filter.getMetaField();

		String relationship = field.getRelationship();
		String condition = "";

		if (relationship != null) {
			condition = filterService.getRelationalCondition(filter, param);
		} else {
			condition = filterService.getSimpleCondition(filter, param);
		}

		if (domain == null) {
			domain = condition;
		} else {
			String opt = filter.getLogicOp() == 0 ? " AND " : " OR ";
			domain = domain + opt + condition;
		}

		return domain;

	}

	private ActionMethod createActionEmail(MetaModel model,
			ActionBuilder builder) {

		if (model == null) {
			return null;
		}

		Template template = builder.getEmailTemplate();

		ActionMethod action = new ActionMethod();
		action.setName(builder.getName());
		Call call = new Call();
		call.setMethod("generateMessage(id, '" + model.getFullName() + "'"
				+ ", '" + model.getName() + "'" + ", '" + template.getName()
				+ "')");
		call.setCondition("id != null");
		call.setController("com.axelor.studio.service.ActionBuilderService");
		action.setCall(call);

		return action;
	}

	public void generateMessage(long id, String model, String tag,
			String templateName) throws ClassNotFoundException,
			InstantiationException, IllegalAccessException, AxelorException,
			IOException {

		Template template = Beans.get(TemplateRepository.class).findByName(
				templateName);

		if (template != null) {
			Beans.get(TemplateMessageService.class).generateMessage(id, model,
					tag, template);
		}

	}

	private ActionValidate createActionValidation(ActionBuilder builder) {

		ActionValidate action = new ActionValidate();
		action.setName(builder.getName());

		List<Validator> validators = new ArrayList<ActionValidate.Validator>();

		for (ActionBuilderLine line : builder.getLines()) {
			Validator validator = null;
			switch (line.getValidationTypeSelect()) {
			case "error":
				validator = new Error();
				break;
			case "alert":
				validator = new Alert();
				break;
			case "info":
				validator = new Info();
				break;
			case "notify":
				validator = new Notify();
				break;
			default:
				continue;
			}
			
			validator.setMessage(line.getValidationMsg());
			String condition = line.getConditionText();
			if(condition != null &!checkGroovy(condition, true)){
				addError(builder.getName(), condition, true);
				continue;
			}
			validator.setCondition(condition);
			validators.add(validator);
		}
		
		if(validators.isEmpty()){
			return null;
		}
		
		action.setValidators(validators);

		return action;
	}
	
	@Transactional
	public static String getSequence(String seqName) {
		
		return JpaSequence.nextValue(seqName);
		
	}
	
	public boolean checkGroovy(String expr, boolean condition) {
		
//		try {
//			if(condition){
//				expr = "if(" + expr + "){}";
//			}
//			new GroovyShell().parse(expr);
//		} catch(MultipleCompilationErrorsException cfe) {
//			cfe.printStackTrace();
//			return false;
//		}
//		
		return true;
	}
	
	private void addError(String builder, String expr, boolean condition){
		
		errors = "";
		if(condition){
			errors += String.format(I18n.get("Invalid condition action builder: %s"
					+ " , condition %s"), builder , expr);
		}
		else{
			errors += String.format(I18n.get("Invalid expression action builder: %s"
				+ " , expression %s"), builder ,  expr);
		}
	}
}
