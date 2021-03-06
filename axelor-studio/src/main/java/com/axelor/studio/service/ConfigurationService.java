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
package com.axelor.studio.service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axelor.app.AppSettings;
import com.axelor.common.FileUtils;
import com.axelor.exception.AxelorException;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.studio.db.StudioConfiguration;
import com.axelor.studio.db.repo.StudioConfigurationRepository;
import com.google.common.base.Strings;
import com.google.inject.Singleton;

/**
 * Service provide configuration details support. It check build directory path
 * and create custom module's directory structure.
 * 
 * @author axelor
 *
 */
@Singleton
public final class ConfigurationService {

	private File domainDir;

	private File viewDir;
	
	private File moduleDir;

	private String moduleName;

	private String depends;

	protected Logger log = LoggerFactory.getLogger(getClass());
	
	/**
	 * Root method of service that check build directory,resource directory for
	 * domain and views. It also call method to create custom module directory .
	 * 
	 * @return
	 * @throws AxelorException 
	 */
	public void config() throws AxelorException {

		log.debug("Configuration service called");

		File buildDirectory = getBuildDirectory();
		if (buildDirectory == null) {
			throw new AxelorException(I18n.get("Build directory not exist"), 4);
		}
		
		
		moduleName = "axelor-custom";
		
		StudioConfiguration config = Beans
				.get(StudioConfigurationRepository.class).all().fetchOne();
		if (config != null) {
			moduleName = config.getName();
			depends = config.getDepends();
		}

		moduleDir = getCustomModule(buildDirectory);
		File resourceDir = getResourceDir(moduleDir);
		domainDir = getDir(resourceDir, "domains");
		viewDir = getDir(resourceDir, "views");

		log.debug("Resource directory found: {}", resourceDir.getPath());

	}

	public File getViewDir() {
		return this.viewDir;
	}

	public File getDomainDir() {
		return this.domainDir;
	}
	
	public File getModuleDir() {
		return this.moduleDir;
	}

	public String getModuleName() {
		return this.moduleName;
	}

	/**
	 * Method to get build directory from property setting.
	 * 
	 * @return
	 */
	private File getBuildDirectory() {

		String buildPath = AppSettings.get().get("build.dir");

		if (buildPath != null) {
			File buildDir = new File(buildPath);
			if (buildDir.exists() && buildDir.isDirectory()) {
				return buildDir;
			}
		}

		return null;
	}

	/**
	 * Create resource directory structure(with src,main,resource) inside given
	 * custom module directory.
	 * 
	 * @param moduleDir
	 *            Custom module directory.
	 * @return Resource directory file.
	 */
	private File getResourceDir(File moduleDir) {

		File resourceDir = FileUtils.getFile(moduleDir, "src", "main",
				"resources");
		if (!resourceDir.exists()) {
			resourceDir.mkdirs();
		}

		return resourceDir;
	}

	/**
	 * Create directory inside given directory. Used for 'views' and 'domains'
	 * directory creation.
	 * 
	 * @param resourceDir
	 *            Resource directory.
	 * @param rootName
	 *            Name of directory to create
	 * @return New Directory file created.
	 * @throws IOException
	 *             Exception thrown in directory creation.
	 */
	public File getDir(File resourceDir, String rootName) {

		File rootDir = FileUtils.getFile(resourceDir, rootName);

		if (!rootDir.exists()) {
			rootDir.mkdir();
		}

		return rootDir;
	}

	/**
	 * Create custom module directory inside root build directory given in
	 * configuration. It also create build file(build.gradle) for custom module.
	 * 
	 * @param buildDir
	 *            Build directory file.
	 * @return Custom module directory created.
	 * @throws AxelorException 
	 */
	private File getCustomModule(File buildDir) throws AxelorException {

		File moduleDir = FileUtils.getFile(buildDir, "modules", moduleName);

		if (!moduleDir.exists()) {
			moduleDir.mkdir();
		}

		File buildFile = FileUtils.getFile(moduleDir, "build.gradle");

		createBuildFile(buildFile);

		return moduleDir;
	}

	/**
	 * Method to write build file(build.gradle) content for custom module.
	 * 
	 * @param buildFile
	 *            Blank buildFile.
	 * @throws AxelorException 
	 */
	private void createBuildFile(File buildFile) throws AxelorException {

		try {
			FileWriter fw = new FileWriter(buildFile);
			String buildText = "apply plugin: 'axelor-module' \n"
					+ "module { \n" + "\t\t name \"" + moduleName + "\"\n"
					+ "\t\t title \"" + moduleName.toUpperCase() + " \"\n"
					+ "\t\t description \"\"\"\\\n"
					+ "Module generated by axelor studio\n" + "\"\"\"\n";

			if (!Strings.isNullOrEmpty(depends)) {
				for (String depend : depends.split(",")) {
					buildText += "\t\t module \"modules:" + depend + "\"\n";
				}
			}

			buildText += "\t\t removable false\n}";
			fw.write(buildText);
			fw.close();
		} catch (IOException e) {
			throw new AxelorException(e, 4);
		}
	}
	
//	private String getDepends() {
//		
//		List<String> modules = new ArrayList<String>();
//		
//		for (MetaModule module : metaModuleRepo.all().fetch()){
//			if (module.getInstalled()) {
//				modules.add(module.getName());
//			}
//		}
//		
//		return Joiner.on(",").join(modules);
//	}

}
