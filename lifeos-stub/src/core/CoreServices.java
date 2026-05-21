package core;

import java.nio.file.Path;

/**
 * Vom LifeOS-Host bereitgestellte Dienste fuer Module.
 *
 * Compile-Stub: Es sind nur die Methoden abgebildet, die das LifeKalender-Modul
 * tatsaechlich aufruft. Der Host stellt die vollstaendige Schnittstelle zur
 * Laufzeit bereit.
 */
public interface CoreServices {

    Path getModuleStoragePath(String moduleId);

    void sendNotification(String message);

    String completeWithInternalLLM(String prompt);
}
