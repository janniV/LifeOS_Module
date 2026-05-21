package lifesuite;

// Startklasse der ausführbaren JAR-Datei.
// Die JAR-Manifestangabe Main-Class verweist auf diese Klasse und nicht direkt auf die
// JavaFX-Application-Klasse. Dadurch lässt sich die Anwendung mit "java -jar" starten,
// ohne dass JavaFX als benanntes Modul auf dem Modulpfad liegen muss: Da die Main-Class
// selbst nicht von Application erbt, entfällt die Prüfung der JavaFX-Laufzeitmodule.
public class Launcher {

    public static void main(String[] args) {
        LifeSuiteStandalone.main(args);
    }
}
