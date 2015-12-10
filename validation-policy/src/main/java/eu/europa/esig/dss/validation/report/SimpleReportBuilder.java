/**
 * DSS - Digital Signature Services
 * Copyright (C) 2015 European Commission, provided under the CEF programme
 *
 * This file is part of the "DSS - Digital Signature Services" project.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package eu.europa.esig.dss.validation.report;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import eu.europa.esig.dss.DSSException;
import eu.europa.esig.dss.DateUtils;
import eu.europa.esig.dss.TSLConstant;
import eu.europa.esig.dss.XmlDom;
import eu.europa.esig.dss.jaxb.diagnostic.XmlCertificate;
import eu.europa.esig.dss.jaxb.diagnostic.XmlSignature;
import eu.europa.esig.dss.jaxb.diagnostic.XmlSignatureScopes;
import eu.europa.esig.dss.jaxb.diagnostic.XmlSigningCertificateType;
import eu.europa.esig.dss.validation.policy.CertificateQualification;
import eu.europa.esig.dss.validation.policy.ProcessParameters;
import eu.europa.esig.dss.validation.policy.SignatureQualification;
import eu.europa.esig.dss.validation.policy.SignatureType;
import eu.europa.esig.dss.validation.policy.TLQualification;
import eu.europa.esig.dss.validation.policy.ValidationPolicy;
import eu.europa.esig.dss.validation.policy.XmlNode;
import eu.europa.esig.dss.validation.policy.rules.AttributeName;
import eu.europa.esig.dss.validation.policy.rules.AttributeValue;
import eu.europa.esig.dss.validation.policy.rules.Indication;
import eu.europa.esig.dss.validation.policy.rules.MessageTag;
import eu.europa.esig.dss.validation.policy.rules.NodeName;
import eu.europa.esig.dss.validation.policy.rules.SubIndication;

/**
 * This class builds a SimpleReport XmlDom from the diagnostic data and detailed validation report.
 */
public class SimpleReportBuilder {

	private static final Logger LOG = LoggerFactory.getLogger(SimpleReportBuilder.class);

	private final ValidationPolicy constraintData;
	private final DiagnosticDataWrapper diagnosticData;

	private int totalSignatureCount = 0;
	private int validSignatureCount = 0;

	public SimpleReportBuilder(final ValidationPolicy constraintData, final DiagnosticDataWrapper diagnosticData) {

		this.constraintData = constraintData;
		this.diagnosticData = diagnosticData;
	}

	/**
	 * This method generates the validation simpleReport.
	 *
	 * @param params
	 *            validation process parameters
	 * @return the object representing {@code SimpleReport}
	 */
	public SimpleReport build(final ProcessParameters params) {

		final XmlNode simpleReport = new XmlNode(NodeName.SIMPLE_REPORT);
		simpleReport.setNameSpace(XmlDom.NAMESPACE);

		try {

			addPolicyNode(simpleReport);

			addValidationTime(params, simpleReport);

			addDocumentName(simpleReport);

			addSignatures(params, simpleReport);

			addStatistics(simpleReport);
		} catch (Exception e) {

			if (!"WAS TREATED".equals(e.getMessage())) {

				notifyException(simpleReport, e);
			}
		}
		final Document reportDocument = simpleReport.toDocument();
		return new SimpleReport(reportDocument);
	}

	private void addPolicyNode(final XmlNode report) {

		final XmlNode policyNode = report.addChild(NodeName.POLICY);
		final String policyName = constraintData.getPolicyName();
		final String policyDescription = constraintData.getPolicyDescription();
		policyNode.addChild(NodeName.POLICY_NAME, policyName);
		policyNode.addChild(NodeName.POLICY_DESCRIPTION, policyDescription);
	}

	private void addValidationTime(final ProcessParameters params, final XmlNode report) {
		final Date validationTime = params.getCurrentTime();
		report.addChild(NodeName.VALIDATION_TIME, DateUtils.formatDate(validationTime));
	}

	private void addDocumentName(final XmlNode report) {
		final String documentName = diagnosticData.getDocumentName();
		report.addChild(NodeName.DOCUMENT_NAME, documentName);
	}

	private void addSignatures(final ProcessParameters params, final XmlNode simpleReport) throws DSSException {

		final List<XmlSignature> signatures = diagnosticData.getSignatures();
		validSignatureCount = 0;
		totalSignatureCount = 0;
		for (final XmlSignature signatureXmlDom : signatures) {
			addSignature(params, simpleReport, signatureXmlDom);
		}
	}

	private void addStatistics(XmlNode report) {

		report.addChild(NodeName.VALID_SIGNATURES_COUNT, Integer.toString(validSignatureCount));
		report.addChild(NodeName.SIGNATURES_COUNT, Integer.toString(totalSignatureCount));
	}

	/**
	 * @param params
	 *            validation process parameters
	 * @param simpleReport
	 * @param diagnosticSignature
	 *            the diagnosticSignature element in the diagnostic data
	 * @throws DSSException
	 */
	private void addSignature(final ProcessParameters params, final XmlNode simpleReport, final XmlSignature diagnosticSignature) throws DSSException {

		totalSignatureCount++;

		final XmlNode signatureNode = simpleReport.addChild(NodeName.SIGNATURE);

		final String signatureId = diagnosticSignature.getId();
		signatureNode.setAttribute(AttributeName.ID, signatureId);

		final String type = diagnosticSignature.getType();
		addCounterSignature(diagnosticSignature, signatureNode, type);
		try {

			addSigningTime(diagnosticSignature, signatureNode);
			addSignatureFormat(diagnosticSignature, signatureNode);

			String certificateId = StringUtils.EMPTY;
			XmlSigningCertificateType signingCertificate = diagnosticSignature.getSigningCertificate();
			if (signingCertificate != null) {
				certificateId = signingCertificate.getId();
			}

			addSignedBy(signatureNode, certificateId);

			XmlDom bvData = params.getBvData();
			final XmlDom basicValidationConclusion = bvData.getElement("/BasicValidationData/Signature[@Id='%s']/Conclusion", signatureId);
			final XmlDom ltvDom = params.getLtvData();
			final XmlDom ltvConclusion = ltvDom.getElement("/LongTermValidationData/Signature[@Id='%s']/Conclusion", signatureId);
			final String ltvIndication = ltvConclusion.getValue("./Indication/text()");
			final String ltvSubIndication = ltvConclusion.getValue("./SubIndication/text()");
			final List<XmlDom> ltvInfoList = ltvConclusion.getElements("./Info");

			String indication = ltvIndication;
			String subIndication = ltvSubIndication;
			List<XmlDom> infoList = new ArrayList<XmlDom>();
			infoList.addAll(ltvInfoList);

			final List<XmlDom> basicValidationInfoList = basicValidationConclusion.getElements("./Info");
			final List<XmlDom> basicValidationWarningList = basicValidationConclusion.getElements("./Warning");
			final List<XmlDom> basicValidationErrorList = basicValidationConclusion.getElements("./Error");

			final boolean noTimestamp = Indication.INDETERMINATE.equals(ltvIndication) && SubIndication.NO_TIMESTAMP.equals(ltvSubIndication);
			if (noTimestamp) {

				final String basicValidationConclusionIndication = basicValidationConclusion.getValue("./Indication/text()");
				final String basicValidationConclusionSubIndication = basicValidationConclusion.getValue("./SubIndication/text()");
				indication = basicValidationConclusionIndication;
				subIndication = basicValidationConclusionSubIndication;
				infoList = basicValidationInfoList;
				if (!Indication.VALID.equals(basicValidationConclusionIndication)) {

					if (noTimestamp) {

						final XmlNode xmlNode = new XmlNode(NodeName.WARNING, MessageTag.LABEL_TINTWS, null);
						final XmlDom xmlDom = xmlNode.toXmlDom();
						infoList.add(xmlDom);
					} else {

						final XmlNode xmlNode = new XmlNode(NodeName.WARNING, MessageTag.LABEL_TINVTWS, null);
						final XmlDom xmlDom = xmlNode.toXmlDom();
						infoList.add(xmlDom);
						infoList.addAll(ltvInfoList);
					}
				}
			}
			signatureNode.addChild(NodeName.INDICATION, indication);
			if (Indication.VALID.equals(indication)) {
				validSignatureCount++;
			}
			if (!subIndication.isEmpty()) {

				signatureNode.addChild(NodeName.SUB_INDICATION, subIndication);
			}
			if (basicValidationConclusion != null) {
				String errorMessage = diagnosticSignature.getErrorMessage();
				if (StringUtils.isNotEmpty(errorMessage)) {
					errorMessage = StringEscapeUtils.escapeXml(errorMessage);
					final XmlNode xmlNode = new XmlNode(NodeName.INFO, errorMessage);
					final XmlDom xmlDom = xmlNode.toXmlDom();
					infoList.add(xmlDom);
				}
			}
			if (!Indication.VALID.equals(ltvIndication)) {

				addBasicInfo(signatureNode, basicValidationErrorList);
			}
			addBasicInfo(signatureNode, basicValidationWarningList);
			addBasicInfo(signatureNode, infoList);

			addSignatureProfile(signatureNode, certificateId);

			final XmlSignatureScopes signatureScopes = diagnosticSignature.getSignatureScopes();
			addSignatureScope(signatureNode, signatureScopes);
		} catch (Exception e) {

			notifyException(signatureNode, e);
			throw new DSSException("WAS TREATED", e);
		}
	}

	private void addCounterSignature(XmlSignature diagnosticSignature, XmlNode signatureNode, String type) {
		if (AttributeValue.COUNTERSIGNATURE.equals(type)) {

			signatureNode.setAttribute(AttributeName.TYPE, AttributeValue.COUNTERSIGNATURE);
			final String parentId = diagnosticSignature.getParentId();
			signatureNode.setAttribute(AttributeName.PARENT_ID, parentId);
		}
	}

	private void addSignatureScope(final XmlNode signatureNode, final XmlSignatureScopes signatureScopes) {
		if (signatureScopes != null) {
			signatureNode.addChild(signatureScopes);
		}
	}

	private void addBasicInfo(final XmlNode signatureNode, final List<XmlDom> basicValidationErrorList) {
		for (final XmlDom error : basicValidationErrorList) {

			signatureNode.addChild(error);
		}
	}

	private void addSigningTime(final XmlSignature diagnosticSignature, final XmlNode signatureNode) {
		signatureNode.addChild(NodeName.SIGNING_TIME, DateUtils.formatDate(diagnosticSignature.getDateTime()));
	}

	private void addSignatureFormat(final XmlSignature diagnosticSignature, final XmlNode signatureNode) {
		signatureNode.setAttribute(NodeName.SIGNATURE_FORMAT, diagnosticSignature.getSignatureFormat());
	}

	private void addSignedBy(final XmlNode signatureNode, final String certificateId) {
		String signedBy = "?";
		if (StringUtils.isNotEmpty(certificateId)) {
			signedBy = diagnosticData.getCertificateDN(certificateId);
		}
		// TODO extract "2.5.4.3"
		signatureNode.addChild(NodeName.SIGNED_BY, signedBy);
	}

	/**
	 * Here we determine the type of the signature.
	 */
	private void addSignatureProfile(XmlNode signatureNode, String certificateId) {
		SignatureType signatureType = SignatureType.NA;
		if (certificateId != null) {
			signatureType = getSignatureType(certificateId);
		}
		signatureNode.addChild(NodeName.SIGNATURE_LEVEL, signatureType.name());
	}

	/**
	 * This method returns the type of the qualification of the signature (signing certificate).
	 *
	 * @param signCert
	 * @return
	 */
	private SignatureType getSignatureType(final String certificateId) {

		XmlCertificate xmlCertificate = diagnosticData.getUsedCertificateByIdNullSafe(certificateId);
		final CertificateQualification certQualification = new CertificateQualification();
		certQualification.setQcp(diagnosticData.isCertificateQCP(xmlCertificate));
		certQualification.setQcpp(diagnosticData.isCertificateQCPPlus(xmlCertificate));
		certQualification.setQcc(diagnosticData.isCertificateQCC(xmlCertificate));
		certQualification.setQcsscd(diagnosticData.isCertificateQCSSCD(xmlCertificate));

		final TLQualification trustedListQualification = new TLQualification();

		final String caqc = diagnosticData.getCertificateTSPServiceType(xmlCertificate);

		final List<String> qualifiers = diagnosticData.getCertificateTSPServiceQualifiers(xmlCertificate);

		trustedListQualification.setCaqc(TSLConstant.CA_QC.equals(caqc));
		trustedListQualification.setQcCNoSSCD(isQcNoSSCD(qualifiers));
		trustedListQualification.setQcForLegalPerson(isQcForLegalPerson(qualifiers));
		trustedListQualification.setQcSSCDAsInCert(isQcSscdStatusAsInCert(qualifiers));
		trustedListQualification.setQcWithSSCD(isQcWithSSCD(qualifiers));

		final SignatureType signatureType = SignatureQualification.getSignatureType(certQualification, trustedListQualification);
		return signatureType;
	}

	private boolean isQcNoSSCD(final List<String> qualifiers) {
		return qualifiers.contains(TSLConstant.QC_NO_SSCD) || qualifiers.contains(TSLConstant.QC_NO_SSCD_119612);
	}

	private boolean isQcForLegalPerson(final List<String> qualifiers) {
		return qualifiers.contains(TSLConstant.QC_FOR_LEGAL_PERSON) || qualifiers.contains(TSLConstant.QC_FOR_LEGAL_PERSON_119612);
	}

	private boolean isQcSscdStatusAsInCert(final List<String> qualifiers) {
		return qualifiers.contains(TSLConstant.QCSSCD_STATUS_AS_IN_CERT) || qualifiers.contains(TSLConstant.QCSSCD_STATUS_AS_IN_CERT_119612);
	}

	private boolean isQcWithSSCD(final List<String> qualifiers) {
		return qualifiers.contains(TSLConstant.QC_WITH_SSCD) || qualifiers.contains(TSLConstant.QC_WITH_SSCD_119612);
	}

	/**
	 * @param signatureNode
	 * @param exception
	 */
	private static void notifyException(final XmlNode signatureNode, final Exception exception) {

		LOG.error(exception.getMessage(), exception);

		signatureNode.removeChild(NodeName.INDICATION);
		signatureNode.removeChild(NodeName.SUB_INDICATION);

		signatureNode.addChild(NodeName.INDICATION, Indication.INDETERMINATE.name());
		signatureNode.addChild(NodeName.SUB_INDICATION, SubIndication.UNEXPECTED_ERROR.name());

		final String message = getSummaryMessage(exception, SimpleReportBuilder.class);
		signatureNode.addChild(NodeName.INFO, message);
	}

	/**
	 * This method returns the summary of the given exception. The analysis of the stack trace stops when the provided class is found.
	 *
	 * @param exception
	 *            {@code Exception} to summarize
	 * @param javaClass
	 *            {@code Class}
	 * @return {@code String} containing the summary message
	 */
	private static String getSummaryMessage(final Exception exception, final Class<?> javaClass) {

		final String javaClassName = javaClass.getName();
		final StackTraceElement[] stackTrace = exception.getStackTrace();
		String message = "See log file for full stack trace.\n";
		message += exception.toString() + '\n';
		for (StackTraceElement element : stackTrace) {

			final String className = element.getClassName();
			if (className.equals(javaClassName)) {

				message += element.toString() + '\n';
				break;
			}
			message += element.toString() + '\n';
		}
		return message;
	}
}
