/**
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) 2021 Source Auditor Inc.
 */
package org.spdx.spdx_to_osv;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.spdx.library.InvalidSPDXAnalysisException;
import org.spdx.library.model.ExternalRef;
import org.spdx.spdx_to_osv.osvmodel.OsvPackage;
import org.spdx.spdx_to_osv.osvmodel.OsvVulnerabilityRequest;

import us.springett.parsers.cpe.Cpe;
import us.springett.parsers.cpe.CpeParser;
import us.springett.parsers.cpe.exceptions.CpeParsingException;
import us.springett.parsers.cpe.values.Part;

/**
 * Parses an ExternalRef
 * 
 * @author Gary O'Neall
 *
 */
public class ExternalRefParser {
    
	static final Pattern SWH_PATTERN = Pattern.compile("swh:1:(cnt|dir|rev|rel|snp):([0123456789abcdef]{40})$");
    static final Pattern PURL_PATTERN = Pattern.compile("pkg:((?<type>[^?/#@]+)/)((?<namespace>[^?#]+)/)?(?<name>[^?#@]+)(@(?<version>[^?#]+))?(\\?[^#]+)*(#.+)*$");

    private boolean useMavenGroupInPkgName = true;
	private ExternalRef externalRef;
    Optional<OsvVulnerabilityRequest> osvVulnerabilityRequest = Optional.empty();
    Optional<Part> cpePart = Optional.empty();
    Optional<String> vendor = Optional.empty(); // CPE vendor
    Optional<String> update = Optional.empty(); // CPE update
    Optional<String> edition = Optional.empty(); // CPE edition
    Optional<String> language = Optional.empty(); // CPE language
    private Optional<String> swEdition = Optional.empty(); // CPE sw_edition
    private Optional<String> targetSw = Optional.empty(); // CPE target_sw
    private Optional<String> targetHw = Optional.empty(); // CPE target_hw
    private Optional<String> cpeOther = Optional.empty(); // CPE other
    
    /**
     * @param externalRef The ExteranlRef to be parsed
     * @throws InvalidSPDXAnalysisException
     * @throws InvalidExternalRefPattern
     * @throws IOException
     * @throws SwhException
     */
    public ExternalRefParser(ExternalRef externalRef) throws InvalidSPDXAnalysisException, InvalidExternalRefPattern, IOException, SwhException {
    	this(externalRef, true);
    }

    /**
     * @param externalRef The ExteranlRef to be parsed
     * @param useMavenGroupInPkgName flag to indicate if the maven group name should be included in the package name (e.g. org.spdx.spdx.spdx-java-tools)
     * @throws InvalidSPDXAnalysisException
     * @throws InvalidExternalRefPattern
     * @throws IOException
     * @throws SwhException
     */
    public ExternalRefParser(ExternalRef externalRef, boolean useMavenGroupInPkgName) throws InvalidSPDXAnalysisException, InvalidExternalRefPattern, IOException, SwhException {
        this.useMavenGroupInPkgName = useMavenGroupInPkgName;
    	this.externalRef = externalRef;
        // Parse the PackageNameVersion
        if (externalRef.getReferenceType().getIndividualURI().endsWith("/cpe22Type")) {
            parseCpe(externalRef.getReferenceLocator());
        } else if (externalRef.getReferenceType().getIndividualURI().endsWith("/cpe23Type")) {
            parseCpe(externalRef.getReferenceLocator());
        } else if (externalRef.getReferenceType().getIndividualURI().endsWith("/maven-central")) {
            parseMavenCentral(externalRef.getReferenceLocator());
        } else if (externalRef.getReferenceType().getIndividualURI().endsWith("/npm")) {
            parseNpm(externalRef.getReferenceLocator());
        } else if (externalRef.getReferenceType().getIndividualURI().endsWith("/nuget")) {
            parseNuget(externalRef.getReferenceLocator());
        } else if (externalRef.getReferenceType().getIndividualURI().endsWith("/bower")) {
            parseBower(externalRef.getReferenceLocator());
        } else if (externalRef.getReferenceType().getIndividualURI().endsWith("/purl")) {
            paserPurl(externalRef.getReferenceLocator());
        } else if (externalRef.getReferenceType().getIndividualURI().endsWith("/swh")) {
            parseSwh(externalRef.getReferenceLocator());
        } else {
            osvVulnerabilityRequest = Optional.empty();
        }
    }

    /**
     * @param referenceLocator
     * @throws InvalidExternalRefPattern 
     * @throws SwhException 
     * @throws IOException 
     */
    private void parseSwh(String referenceLocator) throws InvalidExternalRefPattern, IOException, SwhException {
        Matcher matcher = SWH_PATTERN.matcher(referenceLocator);
        if (!matcher.matches()) {
        	throw new InvalidExternalRefPattern("Software Heritage reference locator '"+referenceLocator+
        			"' does not match the pattern '"+SWH_PATTERN.toString());
        }
        this.osvVulnerabilityRequest = Optional.of(new OsvVulnerabilityRequest(matcher.group(2)));
    }

    /**
     * @param referenceLocator
     * @throws InvalidExternalRefPattern 
     */
    private void paserPurl(String referenceLocator) throws InvalidExternalRefPattern {
        if (referenceLocator.contains("%40")) {
            referenceLocator = referenceLocator.replace("%40", "@");
        }
        Matcher match = PURL_PATTERN.matcher(referenceLocator);
        if (!match.matches()) {
        	throw new InvalidExternalRefPattern("Purl reference locator '"+referenceLocator+
        			"' does not match the pattern '"+PURL_PATTERN.toString());
        }
        String type = match.group("type");
        String packageName = match.group("name");
        String version = match.group("version");
        if ("github".equals(type) || "bitbucket".equals(type)) {
        	this.osvVulnerabilityRequest = Optional.of(new OsvVulnerabilityRequest(version));
        } else if ("docker".equals(type) && Objects.nonNull(version) && version.startsWith("sha256:")) {
        	this.osvVulnerabilityRequest = Optional.of(new OsvVulnerabilityRequest(version.substring("sha256:".length())));
        } else if ("maven".equals(type) && this.useMavenGroupInPkgName) {
        	packageName = match.group("namespace").replaceAll("/", ".") + ":" + packageName;
        	this.osvVulnerabilityRequest = Optional.of(new OsvVulnerabilityRequest(
	        		new OsvPackage(packageName, purlTypeToOsvEcosystem(type), 
	        				referenceLocator), version));
        } else {
	        this.osvVulnerabilityRequest = Optional.of(new OsvVulnerabilityRequest(
	        		new OsvPackage(packageName, purlTypeToOsvEcosystem(type), 
	        				referenceLocator), version));
        }
    }

    /**
	 * @param purlType purl type
	 * @return Osv Scheme
	 */
	private String purlTypeToOsvEcosystem(String purlType) {
		if (Objects.isNull(purlType) || purlType.isEmpty()) {
			return "OSS-Fuzz";
		} else if (purlType.equals("pypi")) {
			return "PyPI";
		} else if (purlType.equals("golang")) {
			return "Go";
		} else if (purlType.equals("maven")) {
			return "Maven";
		} else if (purlType.equals("npm")) {
			return "npm";
		} else if (purlType.equals("nuget")) {
			return "NuGet";
		} else {
			return "OSS-Fuzz";
		}
	}

	/**
     * @param referenceLocator
     */
    private void parseBower(String referenceLocator) {
        String[] parts = referenceLocator.split("#");
        cpePart = Optional.of(Part.APPLICATION);
        String pkg = parts[0];
        String version = null;
        if (parts.length > 1) {
        	version = parts[1];
        }
        
        this.osvVulnerabilityRequest = Optional.of(new OsvVulnerabilityRequest(
        		new OsvPackage(pkg), version));
    }

    /**
     * @param referenceLocator
     */
    private void parseNuget(String referenceLocator) {
        String[] parts = referenceLocator.split("/");
        cpePart = Optional.of(Part.APPLICATION);
        String pkg = parts[0];
        String purl = "pkg:nuget/" + pkg;
        String version = null;
        if (parts.length > 1) {
        	version = parts[1];
        	purl = purl + "@" + version;
        }
        
        this.osvVulnerabilityRequest = Optional.of(new OsvVulnerabilityRequest(
        		new OsvPackage(pkg, "NuGet", purl), version));
    }

    /**
     * @param referenceLocator
     */
    private void parseNpm(String referenceLocator) {
        String[] parts = referenceLocator.split("@");
        cpePart = Optional.of(Part.APPLICATION);
        String pkg = parts[0];
        String purl = "pkg:npm/" + pkg.replaceAll("@", "%40");
        String version = null;
        if (parts.length > 1) {
        	version = parts[1];
        	purl = purl + "@" + version;
        }
        
        this.osvVulnerabilityRequest = Optional.of(new OsvVulnerabilityRequest(
        		new OsvPackage(pkg, "npm", purl), version));
    }

    /**
     * @param referenceLocator
     * @throws InvalidExternalRefPattern 
     */
    private void parseMavenCentral(String referenceLocator) throws InvalidExternalRefPattern {
        String[] parts = referenceLocator.split(":");
        if (parts.length < 2) {
            throw new InvalidExternalRefPattern("Maven central string must have at least the group and artifact");
        }
        cpePart = Optional.of(Part.APPLICATION);
        String pkg;
        if (this.useMavenGroupInPkgName) {
        	pkg = parts[0] + ":" + parts[1];
        } else {
        	pkg = parts[1];
        }
        String purl = "pkg:maven/" + parts[0] + "/" + parts[1];
        String version = null;
        if (parts.length > 2) {
        	version = parts[2];
        	purl = purl + "@" + version;
        }
        
        this.osvVulnerabilityRequest = Optional.of(new OsvVulnerabilityRequest(
        		new OsvPackage(pkg, "Maven", purl), version));
    }

 
    /**
     * Parses the CPE22 format per https://cpe.mitre.org/files/cpe-specification_2.2.pdf
     * @param referenceLocator
     * @throws InvalidSPDXAnalysisException 
     * @throws InvalidExternalRefPattern 
     */
    private void parseCpe(String referenceLocator) throws InvalidExternalRefPattern, InvalidSPDXAnalysisException {
        try {
			Cpe parsedCpe = CpeParser.parse(referenceLocator);
			this.cpeOther = Optional.ofNullable(parsedCpe.getOther());
			this.cpePart  = Optional.ofNullable(parsedCpe.getPart());
			this.edition = Optional.ofNullable(parsedCpe.getEdition());
			this.language = Optional.ofNullable(parsedCpe.getLanguage());
			String product = parsedCpe.getProduct();
			String version = parsedCpe.getVersion();
			if (Objects.nonNull(product) && !product.isEmpty()) {
				if (Objects.nonNull(version) && !version.isEmpty()) {
					 osvVulnerabilityRequest = Optional.of(new OsvVulnerabilityRequest(new OsvPackage(product), version));
				} else {
					osvVulnerabilityRequest = Optional.of(new OsvVulnerabilityRequest(new OsvPackage(product), null));
				}
			} else {
				osvVulnerabilityRequest = Optional.empty();
			}
			this.swEdition = Optional.ofNullable(parsedCpe.getEdition());
			this.targetHw = Optional.ofNullable(parsedCpe.getTargetHw());
			this.update = Optional.ofNullable(parsedCpe.getUpdate());
			this.vendor = Optional.ofNullable(parsedCpe.getVendor());
		} catch (CpeParsingException e) {
			throw new InvalidExternalRefPattern("Invalid CPE 2.2 reference",e);
		}
    }

    /**
     * @return the externalRef
     */
    public ExternalRef getExternalRef() {
        return externalRef;
    }

    /**
     * @return the packageNameVersion
     */
    public Optional<OsvVulnerabilityRequest> osvVulnerabilityRequest() {
        return osvVulnerabilityRequest;
    }

    /**
     * @return the cpePart
     */
    public Optional<Part> getCpePart() {
        return cpePart;
    }

    /**
     * @return the vendor
     */
    public Optional<String> getVendor() {
        return vendor;
    }

    /**
     * @return the update
     */
    public Optional<String> getUpdate() {
        return update;
    }

    /**
     * @return the edition
     */
    public Optional<String> getEdition() {
        return edition;
    }

    /**
     * @return the language
     */
    public Optional<String> getLanguage() {
        return language;
    }

    /**
     * @return the swEdition
     */
    public Optional<String> getSwEdition() {
        return swEdition;
    }

    /**
     * @return the targetSw
     */
    public Optional<String> getTargetSw() {
        return targetSw;
    }

    /**
     * @return the targetHw
     */
    public Optional<String> getTargetHw() {
        return targetHw;
    }

    /**
     * @return the cpeOther
     */
    public Optional<String> getCpeOther() {
        return cpeOther;
    }

	/**
	 * @return flag to indicate if the maven group name should be included in the package name (e.g. org.spdx.spdx.spdx-java-tools)
	 */
	public boolean isUseMavenGroupInPkgName() {
		return useMavenGroupInPkgName;
	}

}
