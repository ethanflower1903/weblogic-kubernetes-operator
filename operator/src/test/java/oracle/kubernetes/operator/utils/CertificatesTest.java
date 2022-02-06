// Copyright (c) 2019, 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.utils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import com.meterware.simplestub.Memento;
import oracle.kubernetes.operator.MainDelegate;
import oracle.kubernetes.utils.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.meterware.simplestub.Stub.createStrictStub;
import static oracle.kubernetes.operator.logging.MessageKeys.NO_EXTERNAL_CERTIFICATE;
import static oracle.kubernetes.operator.logging.MessageKeys.NO_INTERNAL_CERTIFICATE;
import static oracle.kubernetes.utils.LogMatcher.containsConfig;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.junit.MatcherAssert.assertThat;

class CertificatesTest {

  private final TestUtils.ConsoleHandlerMemento consoleHandlerMemento = TestUtils.silenceOperatorLogger();
  private final Collection<LogRecord> logRecords = new ArrayList<>();
  private final MainDelegateStub mainDelegate = createStrictStub(MainDelegateStub.class);

  private final List<Memento> mementos = new ArrayList<>();

  @BeforeEach
  public void setUp() throws Exception {
    mementos.add(consoleHandlerMemento
          .collectLogMessages(logRecords, NO_INTERNAL_CERTIFICATE, NO_EXTERNAL_CERTIFICATE)
          .withLogLevel(Level.FINE));
    mementos.add(InMemoryCertificates.installWithoutData());
  }

  @AfterEach
  public void tearDown() {
    mementos.forEach(Memento::revert);
  }

  @Test
  void whenNoExternalKeyFile_returnNull() {
    Certificates certificates = new Certificates(mainDelegate);
    assertThat(certificates.getOperatorExternalKeyFilePath(), nullValue());
  }

  @Test
  void whenExternalKeyFileDefined_returnPath() {
    InMemoryCertificates.defineOperatorExternalKeyFile("asdf");

    Certificates certificates = new Certificates(mainDelegate);

    assertThat(
        certificates.getOperatorExternalKeyFilePath(), equalTo("/operator/external-identity/externalOperatorKey"));
  }

  @Test
  void whenNoInternalKeyFile_returnNull() {
    Certificates certificates = new Certificates(mainDelegate);
    assertThat(certificates.getOperatorInternalKeyFilePath(), nullValue());
  }

  @Test
  void whenInternalKeyFileDefined_returnPath() {
    InMemoryCertificates.defineOperatorInternalKeyFile("asdf");

    Certificates certificates = new Certificates(mainDelegate);
    assertThat(
        certificates.getOperatorInternalKeyFilePath(), equalTo("/operator/internal-identity/internalOperatorKey"));
  }

  @Test
  void whenNoExternalCertificateFile_returnNull() {
    consoleHandlerMemento.ignoreMessage(NO_EXTERNAL_CERTIFICATE);

    Certificates certificates = new Certificates(mainDelegate);
    assertThat(certificates.getOperatorExternalCertificateData(), nullValue());
  }

  @Test
  void whenNoExternalCertificateFile_logConfigMessage() {
    Certificates certificates = new Certificates(mainDelegate);
    assertThat(certificates.getOperatorExternalCertificateData(), nullValue());

    assertThat(logRecords, containsConfig(NO_EXTERNAL_CERTIFICATE));
  }

  @Test
  void whenExternalCertificateFileDefined_returnData() {
    InMemoryCertificates.defineOperatorExternalCertificateFile("asdf");

    Certificates certificates = new Certificates(mainDelegate);
    assertThat(certificates.getOperatorExternalCertificateData(), notNullValue());
  }

  @Test
  void whenNoInternalCertificateFile_returnNull() {
    consoleHandlerMemento.ignoreMessage(NO_INTERNAL_CERTIFICATE);

    Certificates certificates = new Certificates(mainDelegate);
    assertThat(certificates.getOperatorInternalCertificateData(), nullValue());
  }

  @Test
  void whenNoInternalCertificateFile_logConfigMessage() {
    Certificates certificates = new Certificates(mainDelegate);
    certificates.getOperatorInternalCertificateData();

    assertThat(logRecords, containsConfig(NO_INTERNAL_CERTIFICATE));
  }

  @Test
  void whenInternalCertificateFileDefined_returnPath() {
    InMemoryCertificates.defineOperatorInternalCertificateFile("asdf");

    Certificates certificates = new Certificates(mainDelegate);
    assertThat(certificates.getOperatorInternalCertificateData(), notNullValue());
  }

  abstract static class MainDelegateStub implements MainDelegate {
    public File getOperatorHome() {
      return new File("/operator");
    }
  }
}
