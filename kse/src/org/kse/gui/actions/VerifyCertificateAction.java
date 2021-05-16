package org.kse.gui.actions;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.swing.JOptionPane;

import org.kse.crypto.CryptoException;
import org.kse.crypto.x509.X509CertUtil;
import org.kse.gui.KseFrame;
import org.kse.gui.dialogs.DVerifyCertificate;
import org.kse.gui.dialogs.DVerifyCertificate.VerifyOptions;
import org.kse.gui.error.DError;
import org.kse.utilities.history.KeyStoreHistory;

public class VerifyCertificateAction extends KeyStoreExplorerAction {

	private static final long serialVersionUID = 1L;
	private X509Certificate certFromConstructor;
	private X509Certificate[] chain;

	public VerifyCertificateAction(KseFrame kseFrame) {
		super(kseFrame);
	}

	public VerifyCertificateAction(KseFrame kseFrame, X509Certificate cert, X509Certificate[] chain) {
		super(kseFrame);
		this.certFromConstructor = cert;
		this.chain = chain;
	}

	@Override
	protected void doAction() {

		try {
			DVerifyCertificate dVerifyCertificate = null;
			X509Certificate cert = null;
			if (certFromConstructor == null) {
				String alias = kseFrame.getSelectedEntryAlias();
				dVerifyCertificate = new DVerifyCertificate(frame, alias);
				cert = getCertificate(alias);
			} else {
				cert = certFromConstructor;
				dVerifyCertificate = new DVerifyCertificate(frame, X509CertUtil.getCertificateAlias(cert));
			}

			dVerifyCertificate.setLocationRelativeTo(frame);
			dVerifyCertificate.setVisible(true);
			if (dVerifyCertificate.isVerifySelected()) {

				VerifyOptions verifyOptions = dVerifyCertificate.getVerifyOption();

				if (verifyOptions == VerifyOptions.CRL) {
					verifyStatusCrl();
					JOptionPane.showMessageDialog(frame, res.getString("VerifyCertificateAction.CrlSuccessful.message"),
							res.getString("VerifyCertificateAction.Verify.Title"), JOptionPane.INFORMATION_MESSAGE);
				} else if (verifyOptions == VerifyOptions.OCSP) {
					verifyStatusOCSP();
					JOptionPane.showMessageDialog(frame,
							res.getString("VerifyCertificateAction.OcspSuccessful.message"),
							res.getString("VerifyCertificateAction.Verify.Title"), JOptionPane.INFORMATION_MESSAGE);
				} else {
					verifyChain(dVerifyCertificate.getCaCertificateFile());
					JOptionPane.showMessageDialog(frame,
							res.getString("VerifyCertificateAction.ChainSuccessful.message"),
							res.getString("VerifyCertificateAction.Verify.Title"), JOptionPane.INFORMATION_MESSAGE);
				}
			}
		} catch (Exception ex) {
			DError.displayError(frame, ex);
		}
	}

	private void verifyChain(String caCertificateFile) throws KeyStoreException, NoSuchAlgorithmException,
			CertificateException, IOException, InvalidAlgorithmParameterException, CertPathValidatorException {
		verify("false", "false", false, caCertificateFile);
	}

	private void verifyStatusOCSP() throws KeyStoreException, NoSuchAlgorithmException, CertificateException,
			IOException, InvalidAlgorithmParameterException, CertPathValidatorException {
		verify("false", "true", true, "");
	}

	private void verifyStatusCrl() throws CertificateException, KeyStoreException, NoSuchAlgorithmException,
			IOException, InvalidAlgorithmParameterException, CertPathValidatorException {
		verify("true", "false", true, "");
	}

	private void verify(String crl, String ocsp, boolean revocationEnabled, String caCertificateFile)
			throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException,
			InvalidAlgorithmParameterException, CertPathValidatorException {

		KeyStore trustStore = KeyStore.getInstance("JKS");
		if (caCertificateFile.length() > 0) {
			File file = new File(caCertificateFile);
			if (!file.exists()) {
				throw new FileNotFoundException(res.getString("VerifyCertificateAction.FileNotFoundException.message"));
			}
			try (FileInputStream in = new FileInputStream(file)) {
				trustStore.load(in, "changeit".toCharArray());//fix
			}
		} else {
			trustStore.load(null, null);
			for (int i = 0; i < chain.length; i++) {
				X509Certificate cert = chain[i];
				boolean[] keyUsage = cert.getKeyUsage();
				if (keyUsage[5]) {
					// CA certificate
					String entry = "entry" + i;
					trustStore.setCertificateEntry(entry, cert);
				}
			}
		}

		System.setProperty("com.sun.net.ssl.checkRevocation", crl);
		System.setProperty("com.sun.security.enableCRLDP", crl);
		Security.setProperty("ocsp.enable", ocsp);

		List<X509Certificate> certificados = new ArrayList<>();
		if (revocationEnabled) {
			certificados.add(certFromConstructor);
		} else {
			for (int i = chain.length - 1; i >= 0; i--) {
				X509Certificate cert = chain[i];
				certificados.add(0, cert);
				if (cert.equals(certFromConstructor)) {
					break;
				}
			}
		}

		CertPathValidator validator = CertPathValidator.getInstance("PKIX");
		CertificateFactory factory = CertificateFactory.getInstance("X509");
		CertPath certPath = factory.generateCertPath(certificados);
		PKIXParameters params = new PKIXParameters(trustStore);
		Date date = new Date(System.currentTimeMillis());
		params.setDate(date);
		params.setRevocationEnabled(revocationEnabled);
		validator.validate(certPath, params);
	}

	private X509Certificate getCertificate(String alias) throws CryptoException {
		try {
			KeyStoreHistory history = kseFrame.getActiveKeyStoreHistory();
			KeyStore keyStore = history.getCurrentState().getKeyStore();

			return X509CertUtil.convertCertificate(keyStore.getCertificate(alias));
		} catch (KeyStoreException ex) {
			String message = MessageFormat.format(res.getString("VerifyCertificateAction.NoAccessEntry.message"),
					alias);
			throw new CryptoException(message, ex);
		}
	}
}
