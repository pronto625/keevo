---
baseline_commit: 851b722e850b24588fdf97756676739ab5c4a73e
---
# Story 10.6: Filets d'architecture — Spring Modulith `verify` + ArchUnit (baseline *warning*)

Status: done

## Story

As an **architecte**,
I want **une vérification automatique des frontières de modules en CI, en mode non bloquant au départ**,
so that **on mesure le couplage actuel et on empêche sa dégradation avant le découplage (MOD-NFR3, prépare Epic 12)**.

## Contexte

Stories 10.1–10.5 ont sécurisé le socle opérationnel (Flyway baseline, ddl-auto=validate, secrets fail-fast, backups, staging). Cette story installe les **filets d'architecture** : Spring Modulith `verify()` + règles ArchUnit, en mode **warning uniquement** (les violations existantes ne bloquent pas la CI, mais toute nouvelle violation est visible dans le diff du rapport).

**Couplage inter-modules actuel (mesuré, à documenter — pas à corriger ici) :**

| Module | Imports cross-module (hors `shared`) |
|--------|---------------------------------------|
| `sync` | 53 (→ catalog ×29, commerce ×12, inventory ×11, identity ×1) |
| `reporting` | 35 (→ identity ×21, messaging ×6, store ×5, commerce ×3) |
| `inventory` | 24 (→ divers : catalog, commerce, identity) |
| `commerce` | 20 (→ catalog, inventory, identity, store) |
| `catalog` | 16 (→ identity, store, subscription) |
| `admin` | 4 (→ identity, catalog) |

Ces violations sont **intentionnellement capturées, pas résolues** ici. Le découplage est l'objet d'Epic 12.

**Passage en mode bloquant** : Story 12.2e (après que tous les modules ont leurs `api/` propres).

## Acceptance Criteria

1. **(Rapport de modules généré en CI)** **Given** la CI exécute les tests d'architecture, **When** `mvn test` se termine, **Then** un rapport Asciidoc + PlantUML des modules est généré dans `target/modulith-docs/` **ET** publié comme artefact CI (réutilisé en Epic 12 pour valider le découplage progressif).

2. **(verify() non bloquant)** **Given** le code actuel (couplage inter-modules existant), **When** `ApplicationModules.of(KeevoApplication.class).verify()` s'exécute, **Then** le test se termine **sans échouer la build**, même si des violations existent.

3. **(Violations capturées dans un fichier)** **Given** des violations de frontières détectées par Modulith, **When** le test d'architecture s'exécute, **Then** elles sont écrites dans `target/modulith-violations.txt` avec le nombre total — ce fichier est publié comme artefact CI.

4. **(Nouvelle violation visible)** **Given** un développeur introduit un nouvel import croisé (ex. `commerce` → interne de `catalog`), **When** la CI s'exécute, **Then** `target/modulith-violations.txt` contient cette violation (elle apparaît dans le diff du rapport).

5. **(Bounded contexts déclarés comme modules Modulith)** **Given** les packages `com.keevo.{catalog,commerce,inventory,identity,admin,messaging,reporting,store,subscription,sync}`, **When** `ApplicationModules.of(KeevoApplication.class)` est appelé, **Then** chaque bounded context est reconnu comme un module Spring Modulith nommé, et `shared` est déclaré en `OPEN` (accessible à tous sans contrainte).

6. **(Règles ArchUnit baseline — layering)** **Given** la CI, **When** les tests ArchUnit s'exécutent, **Then** un rapport de violations de layering hexagonal est écrit dans `target/archunit-violations.txt` **sans faire échouer la build** (baseline warning).

## Tasks / Subtasks

- [x] **Task 1 — Dépendances `pom.xml` (AC1, AC2, AC5, AC6)**
  - [x] Ajouter un bloc `<dependencyManagement>` avec le BOM Spring Modulith :
    ```xml
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.modulith</groupId>
                <artifactId>spring-modulith-bom</artifactId>
                <version>1.4.0</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
    ```
  - [x] Ajouter dans `<dependencies>` (scope `test`) :
    ```xml
    <!-- Spring Modulith — module verification + documentation -->
    <dependency>
        <groupId>org.springframework.modulith</groupId>
        <artifactId>spring-modulith-starter-test</artifactId>
        <scope>test</scope>
    </dependency>

    <!-- ArchUnit — layering and module boundary rules -->
    <dependency>
        <groupId>com.tngtech.archunit</groupId>
        <artifactId>archunit-junit5</artifactId>
        <version>1.3.0</version>
        <scope>test</scope>
    </dependency>
    ```
  - [x] ⚠️ Vérifier la compatibilité Spring Modulith 1.4.x ↔ Spring Boot 3.5.0 (exécuter `mvn dependency:tree | grep modulith` pour confirmer). Si 1.4.0 n'est pas disponible, utiliser la dernière 1.3.x.
  - [x] ⚠️ `dependencyManagement` doit être ajouté **avant** `<dependencies>` dans le POM mais **après** `<parent>`. S'il n'existe pas encore, l'ajouter.

- [x] **Task 2 — `package-info.java` par bounded context (AC5)**
  - [x] Créer `com/keevo/catalog/package-info.java` :
    ```java
    @org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"identity", "store", "subscription"}
    )
    package com.keevo.catalog;
    ```
  - [x] Créer `com/keevo/commerce/package-info.java` :
    ```java
    @org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"catalog", "inventory", "identity", "store", "subscription", "messaging"}
    )
    package com.keevo.commerce;
    ```
  - [x] Créer `com/keevo/inventory/package-info.java` :
    ```java
    @org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"catalog", "commerce", "identity", "store"}
    )
    package com.keevo.inventory;
    ```
  - [x] Créer `com/keevo/sync/package-info.java` :
    ```java
    @org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"catalog", "commerce", "inventory", "identity", "store", "subscription"}
    )
    package com.keevo.sync;
    ```
  - [x] Créer `com/keevo/reporting/package-info.java` :
    ```java
    @org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"catalog", "commerce", "inventory", "identity", "store", "messaging", "subscription"}
    )
    package com.keevo.reporting;
    ```
  - [x] Créer `com/keevo/admin/package-info.java` :
    ```java
    @org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"catalog", "commerce", "inventory", "identity", "store", "subscription", "sync", "reporting", "messaging"}
    )
    package com.keevo.admin;
    ```
  - [x] Créer `com/keevo/messaging/package-info.java` :
    ```java
    @org.springframework.modulith.ApplicationModule
    package com.keevo.messaging;
    ```
  - [x] Créer `com/keevo/store/package-info.java` :
    ```java
    @org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"identity"}
    )
    package com.keevo.store;
    ```
  - [x] Créer `com/keevo/subscription/package-info.java` :
    ```java
    @org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"identity"}
    )
    package com.keevo.subscription;
    ```
  - [x] Créer `com/keevo/identity/package-info.java` :
    ```java
    @org.springframework.modulith.ApplicationModule
    package com.keevo.identity;
    ```
  - [x] Créer `com/keevo/shared/package-info.java` (OPEN — accessible à tous) :
    ```java
    @org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
    package com.keevo.shared;
    ```
  - [x] ⚠️ Tous ces fichiers vont dans `src/main/java/com/keevo/{module}/package-info.java` (PAS dans `src/test/`).
  - [x] ⚠️ Les `allowedDependencies` à ce stade sont **délibérément permissifs** pour refléter le couplage réel. Ils seront resserrés au fil d'Epic 12. NE PAS inventer des restrictions qui casseraient `verify()`.
  - [x] ⚠️ Si `verify()` lève une violation non couverte par `allowedDependencies`, ajouter le module manquant à la liste (Task 3 gère les violations restantes en catch).
  - [x] ⚠️ Vérifier si `com.keevo.common` existe : si oui, créer aussi `com/keevo/common/package-info.java` avec `@ApplicationModule(type = Type.OPEN)` ou `allowedDependencies` approprié (le répertoire `common/web` a été détecté dans l'analyse).

- [x] **Task 3 — `ApplicationModulesBaselineTest.java` (AC1, AC2, AC3, AC4)**
  - [x] Créer `src/test/java/com/keevo/architecture/ApplicationModulesBaselineTest.java` :
    ```java
    package com.keevo.architecture;

    import com.keevo.KeevoApplication;
    import org.junit.jupiter.api.Test;
    import org.springframework.modulith.core.ApplicationModules;
    import org.springframework.modulith.docs.Documenter;

    import java.io.IOException;
    import java.nio.file.Files;
    import java.nio.file.Path;

    class ApplicationModulesBaselineTest {

        private static final Path VIOLATIONS_FILE =
            Path.of("target/modulith-violations.txt");

        @Test
        void detectModulesAndGenerateDocumentation() throws IOException {
            ApplicationModules modules = ApplicationModules.of(KeevoApplication.class);

            // AC1: Generate C4 + PlantUML docs → target/modulith-docs/
            new Documenter(modules)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml()
                .writeDocumentation();
        }

        @Test
        void verifyModuleBoundaries_warningModeOnly() throws IOException {
            ApplicationModules modules = ApplicationModules.of(KeevoApplication.class);

            // AC2, AC3, AC4: verify() in warning mode — captures violations without failing
            try {
                modules.verify();
                Files.writeString(VIOLATIONS_FILE, "✓ No module violations detected.\n");
            } catch (AssertionError e) {
                // Baseline mode: document violations, do NOT fail the build
                String report = """
                    === Spring Modulith Violation Report (baseline — warning mode) ===
                    Total violations: counted in details below
                    Run: ApplicationModules.of(KeevoApplication.class).verify()
                    Status: NON-BLOCKING (passes in Epic 10, becomes blocking in Epic 12.2e)
                    
                    === Violation Details ===
                    %s
                    """.formatted(e.getMessage());
                Files.createDirectories(VIOLATIONS_FILE.getParent());
                Files.writeString(VIOLATIONS_FILE, report);
                // DO NOT rethrow — warning mode
            }
        }
    }
    ```
  - [x] ⚠️ Ce test NE doit PAS porter `@SpringBootTest` — `ApplicationModules.of()` est une analyse statique du classpath, sans démarrage du contexte Spring. C'est rapide (< 5 sec).
  - [x] ⚠️ Import correct : `org.springframework.modulith.core.ApplicationModules` (pas `org.springframework.modulith.test`).
  - [x] ⚠️ `Documenter` : utiliser l'API enchaînable de Spring Modulith 1.4.x. Si l'API change, consulter les Javadocs du JAR dans `~/.m2`.
  - [x] ⚠️ Vérifier que `target/modulith-docs/` est créé après `mvn test` (sinon le CI artifact step échouera).

- [x] **Task 4 — `KeevoArchUnitBaselineTest.java` (AC6)**
  - [x] Créer `src/test/java/com/keevo/architecture/KeevoArchUnitBaselineTest.java` :
    ```java
    package com.keevo.architecture;

    import com.tngtech.archunit.core.domain.JavaClasses;
    import com.tngtech.archunit.core.importer.ClassFileImporter;
    import com.tngtech.archunit.lang.ArchRule;
    import org.junit.jupiter.api.BeforeAll;
    import org.junit.jupiter.api.Test;

    import java.io.IOException;
    import java.nio.file.Files;
    import java.nio.file.Path;
    import java.util.ArrayList;
    import java.util.List;

    import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

    /**
     * ArchUnit baseline rules — WARNING MODE (non-blocking).
     *
     * Rules document current violations without failing the build.
     * Will be made blocking in Story 12.6.
     */
    class KeevoArchUnitBaselineTest {

        private static final Path VIOLATIONS_FILE =
            Path.of("target/archunit-violations.txt");

        private static JavaClasses keevoClasses;

        @BeforeAll
        static void importClasses() {
            keevoClasses = new ClassFileImporter().importPackages("com.keevo");
        }

        @Test
        void architectureViolations_warningModeOnly() throws IOException {
            List<String> violations = new ArrayList<>();

            // Rule 1: No JPA entities outside adapter/out/persistence
            checkRule(
                noClasses()
                    .that().areAnnotatedWith(jakarta.persistence.Entity.class)
                    .should().resideOutsideOfPackage("..adapter.out.persistence.."),
                "Rule 1: @Entity classes outside adapter/out/persistence",
                violations
            );

            // Rule 2: No Spring web types in domain layer
            checkRule(
                noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.springframework.web.."),
                "Rule 2: Spring web dependencies in domain layer",
                violations
            );

            // Rule 3: No Spring web types in application/service layer
            checkRule(
                noClasses()
                    .that().resideInAPackage("..application.service..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.springframework.web.."),
                "Rule 3: Spring web dependencies in application/service layer",
                violations
            );

            // Write report
            Files.createDirectories(VIOLATIONS_FILE.getParent());
            if (violations.isEmpty()) {
                Files.writeString(VIOLATIONS_FILE, "✓ No ArchUnit violations detected.\n");
            } else {
                Files.writeString(VIOLATIONS_FILE,
                    "=== ArchUnit Violation Report (baseline — warning mode) ===\n" +
                    "Total rules with violations: " + violations.size() + "\n\n" +
                    String.join("\n---\n", violations));
            }
            // DO NOT fail — warning mode until Story 12.6
        }

        private void checkRule(ArchRule rule, String ruleName, List<String> violations) {
            try {
                rule.check(keevoClasses);
            } catch (AssertionError e) {
                violations.add(ruleName + ":\n" + e.getMessage());
            }
        }
    }
    ```
  - [x] ⚠️ L'import `jakarta.persistence.Entity` (pas `javax.persistence`) — Spring Boot 3.x utilise Jakarta EE 10.
  - [x] ⚠️ `ClassFileImporter` scanne le classpath compilé (`target/classes`). Il faut que la compilation soit faite avant (`mvn test` le fait automatiquement).
  - [x] ⚠️ Ces 3 règles sont les règles **minimales de baseline** — ne pas en inventer d'autres. Les règles complémentaires sont l'objet de Story 12.6.

- [x] **Task 5 — Publication des artefacts CI (AC1, AC3)**
  - [x] Modifier `.github/workflows/backend-ci.yml` — ajouter un step `upload-artifact` après les tests :
    ```yaml
    - name: Upload architecture reports
      if: always()   # publish even if tests fail
      uses: actions/upload-artifact@v4
      with:
        name: architecture-reports
        path: |
          keevo/backend/target/modulith-docs/
          keevo/backend/target/modulith-violations.txt
          keevo/backend/target/archunit-violations.txt
        retention-days: 30
    ```
  - [x] ⚠️ `if: always()` est **obligatoire** pour que les rapports soient publiés même si d'autres tests échouent.
  - [x] ⚠️ Le step existant publie `surefire-reports/` — NE PAS le supprimer, juste ajouter ce nouveau step après.
  - [x] ⚠️ Le chemin est `keevo/backend/target/...` (relatif à la racine du repo) car `mvn test` est lancé depuis `keevo/backend/`.

- [x] **Task 6 — Validation (smoke test)**
  - [x] Exécuter `mvn test -pl keevo/backend -am -Dtest="ApplicationModulesBaselineTest,KeevoArchUnitBaselineTest" --no-transfer-progress` depuis la racine du repo.
  - [x] Vérifier que les deux tests passent (aucun `AssertionError` propagé).
  - [x] Vérifier que `keevo/backend/target/modulith-docs/` contient des fichiers `.adoc` et `.puml`.
  - [x] Vérifier que `keevo/backend/target/modulith-violations.txt` existe (contient violations ou "No violations").
  - [x] Vérifier que `keevo/backend/target/archunit-violations.txt` existe.
  - [x] Coller le résultat des tests dans "Dev Agent Record > Completion Notes".

## Dev Notes

### ⚠️ Spring Modulith n'a PAS de BOM dans le Spring Boot Parent POM

Spring Boot 3.5.x (`spring-boot-starter-parent`) **ne gère pas** les versions de Spring Modulith. Il faut impérativement ajouter un bloc `<dependencyManagement>` avec le BOM Modulith. Sans cela, Maven ne résoudra pas les versions de `spring-modulith-*`.

Version recommandée : **Spring Modulith 1.4.0** (compatible Spring Boot 3.5). Si indisponible en dépôt, utiliser `1.3.4` (compatible Spring Boot 3.4 — peut fonctionner avec Boot 3.5 en mode transitoire). Vérifier avec `mvn dependency:resolve -Dartifact=org.springframework.modulith:spring-modulith-starter-test:RELEASE`.

### ⚠️ `ApplicationModules.of()` est une analyse statique — PAS de context Spring

`ApplicationModules.of(KeevoApplication.class)` analyse les packages au moment de la compilation, sans démarrer l'application. Ce n'est **pas** un `@SpringBootTest`. Ne pas ajouter `@SpringBootTest` aux tests d'architecture — cela ralentirait inutilement la CI (30+ secondes) pour une analyse purement statique (< 5 secondes).

### ⚠️ `verify()` lève `AssertionError` en cas de violation

Spring Modulith `verify()` lève une `AssertionError` (pas une exception custom) quand une violation est détectée. Le try-catch doit attraper `AssertionError`, pas `Exception`. Ne pas relancer — c'est le cœur du mode "warning".

### ⚠️ `allowedDependencies` dans `package-info.java` — sémantique

`allowedDependencies = {"catalog"}` dans le module `sync` signifie que `sync` peut accéder aux classes **déclarées dans l'API publique** de `catalog` (i.e., le package `com.keevo.catalog` directement et ses sous-packages non marqués `internal`). Spring Modulith considère tous les sous-packages d'un module comme "internes" sauf `api/` (convention). Puisqu'il n'y a pas encore de `catalog/api/`, `verify()` avec `allowedDependencies` va encore reporter des violations.

**C'est normal et attendu** pour cette story baseline. Le but est de capturer, pas d'éliminer.

### ⚠️ Package `com.keevo.common` — à vérifier

L'analyse a détecté un répertoire `common/web` dans les sources. Si ce package contient des classes, il faut lui créer un `package-info.java`. Analyser son contenu avant de décider du type (`OPEN` ou module normal).

### ⚠️ Pattern `Documenter` dans Modulith 1.4.x

L'API `Documenter` a évolué entre 1.2, 1.3 et 1.4. Vérifier les méthodes disponibles via `mvn javadoc:javadoc` ou en consultant le Javadoc du JAR. La méthode enchaînable `writeDocumentation()` existe depuis 1.2. Si `writeModulesAsPlantUml()` ou `writeIndividualModulesAsPlantUml()` n'existent pas dans la version disponible, utiliser simplement :
```java
new Documenter(modules).writeDocumentation();
```
Cela génère l'Asciidoc et les diagrammes PlantUML dans `target/modulith-docs/`.

### Architecture du test d'architecture — où placer les fichiers

```
src/
  main/java/com/keevo/
    catalog/package-info.java          ← Task 2 (×11 bounded contexts)
    commerce/package-info.java
    ...
  test/java/com/keevo/
    architecture/                      ← NEW (Tasks 3, 4)
      ApplicationModulesBaselineTest.java
      KeevoArchUnitBaselineTest.java
```

### Boundary du périmètre de cette story

**NE PAS** :
- Corriger les violations existantes (cross-module imports) — c'est Epic 12
- Passer `verify()` en mode bloquant — c'est Story 12.2e
- Ajouter des règles ArchUnit pour les couplages croisés — c'est Story 12.6
- Modifier des fichiers Java existants (les `package-info.java` sont des fichiers NOUVEAUX)
- Toucher à la logique métier

**FAIRE** :
- Ajouter deps, créer `package-info.java`, créer les 2 tests, mettre à jour CI

### Story précédente (10-5) — contexte

10-5 était purement infrastructure (staging) : aucune logique Java, aucun test JUnit. Cette story (10-6) revient au code Java. Le pattern de test de référence est `FlywayBaselineIntegrationTest` pour la structure JUnit 5, mais **notre test n'a pas besoin de Testcontainers** (analyse statique).

### Références Spring Modulith

- `ApplicationModules` : `org.springframework.modulith.core.ApplicationModules`
- `Documenter` : `org.springframework.modulith.docs.Documenter`
- `@ApplicationModule` : `org.springframework.modulith.ApplicationModule`
- `ApplicationModule.Type.OPEN` : pour `shared` (accessible sans restriction)
- Docs officielles : https://docs.spring.io/spring-modulith/reference/verification.html

### Références ArchUnit

- `ClassFileImporter` : `com.tngtech.archunit.core.importer.ClassFileImporter`
- `noClasses()` : `com.tngtech.archunit.lang.syntax.ArchRuleDefinition`
- Règle vérifiée via `rule.check(classes)` (lève `AssertionError` si violation)

### Fichiers touchés

| Fichier | Type | Détail |
|---------|------|--------|
| `keevo/backend/pom.xml` | UPDATE | Ajout `<dependencyManagement>` BOM Modulith + 2 dépendances test |
| `keevo/backend/src/main/java/com/keevo/catalog/package-info.java` | NEW | `@ApplicationModule` |
| `keevo/backend/src/main/java/com/keevo/commerce/package-info.java` | NEW | `@ApplicationModule` |
| `keevo/backend/src/main/java/com/keevo/inventory/package-info.java` | NEW | `@ApplicationModule` |
| `keevo/backend/src/main/java/com/keevo/sync/package-info.java` | NEW | `@ApplicationModule` |
| `keevo/backend/src/main/java/com/keevo/reporting/package-info.java` | NEW | `@ApplicationModule` |
| `keevo/backend/src/main/java/com/keevo/admin/package-info.java` | NEW | `@ApplicationModule` |
| `keevo/backend/src/main/java/com/keevo/messaging/package-info.java` | NEW | `@ApplicationModule` |
| `keevo/backend/src/main/java/com/keevo/store/package-info.java` | NEW | `@ApplicationModule` |
| `keevo/backend/src/main/java/com/keevo/subscription/package-info.java` | NEW | `@ApplicationModule` |
| `keevo/backend/src/main/java/com/keevo/identity/package-info.java` | NEW | `@ApplicationModule` |
| `keevo/backend/src/main/java/com/keevo/shared/package-info.java` | NEW | `@ApplicationModule(type = Type.OPEN)` |
| `keevo/backend/src/test/java/com/keevo/architecture/ApplicationModulesBaselineTest.java` | NEW | Detect + document + verify (warning mode) |
| `keevo/backend/src/test/java/com/keevo/architecture/KeevoArchUnitBaselineTest.java` | NEW | Layering rules (warning mode) |
| `.github/workflows/backend-ci.yml` | UPDATE | Ajout upload-artifact architecture-reports |

### Ce qui NE change PAS

- Aucun fichier Java existant (`KeevoApplication.java`, services, repositories, controllers)
- Aucune migration Flyway
- `application.yml`, `application-prod.yml`, `application-staging.yml` : intouchés
- `deploy-backend.yml` : intouché

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

- Versions réelles résolues : **Spring Modulith 1.4.0**, **ArchUnit 1.3.0**
- Résultat `mvn test` tests d'architecture : **3/3 PASS** (2 Modulith + 1 ArchUnit, 0 failures, 0 errors)
- `target/modulith-violations.txt` : cycles détectés (catalog↔identity, catalog↔identity↔messaging) — capturés en mode warning, non bloquants
- Modules détectés par `ApplicationModules.of(KeevoApplication.class)` : **11 modules** (admin, catalog, commerce, identity, inventory, messaging, reporting, shared [OPEN], store, subscription, sync)
- `target/archunit-violations.txt` : **1 règle violée** (Rule 1: `@Entity` classes dans `shared.infrastructure.persistence.entity` — hors `adapter/out/persistence`) — capturée en mode warning
- `target/spring-modulith-docs/` : **25 fichiers** générés (`.adoc` + `.puml` pour chaque module + `components.puml` + `all-docs.adoc`)
- Fix technique : `verify()` lève `Violations` (RuntimeException) en Modulith 1.4, pas `AssertionError` → catch `Exception | AssertionError`
- `spring-modulith-api` ajouté en scope `compile` (requis pour `@ApplicationModule` dans `package-info.java`)

### File List

| File | Type | Detail |
|------|------|--------|
| `keevo/backend/pom.xml` | UPDATE | `<dependencyManagement>` BOM Modulith 1.4.0 + `spring-modulith-api` (compile) + `spring-modulith-starter-test` (test) + `archunit-junit5` 1.3.0 (test) |
| `keevo/backend/src/main/java/com/keevo/catalog/package-info.java` | NEW | `@ApplicationModule(allowedDependencies = {"identity", "store", "subscription"})` |
| `keevo/backend/src/main/java/com/keevo/commerce/package-info.java` | NEW | `@ApplicationModule(allowedDependencies = {"catalog", "inventory", "identity", "store", "subscription", "messaging"})` |
| `keevo/backend/src/main/java/com/keevo/inventory/package-info.java` | NEW | `@ApplicationModule(allowedDependencies = {"catalog", "commerce", "identity", "store"})` |
| `keevo/backend/src/main/java/com/keevo/sync/package-info.java` | NEW | `@ApplicationModule(allowedDependencies = {"catalog", "commerce", "inventory", "identity", "store", "subscription"})` |
| `keevo/backend/src/main/java/com/keevo/reporting/package-info.java` | NEW | `@ApplicationModule(allowedDependencies = {"catalog", "commerce", "inventory", "identity", "store", "messaging", "subscription"})` |
| `keevo/backend/src/main/java/com/keevo/admin/package-info.java` | NEW | `@ApplicationModule(allowedDependencies = {"catalog", "commerce", "inventory", "identity", "store", "subscription", "sync", "reporting", "messaging"})` |
| `keevo/backend/src/main/java/com/keevo/messaging/package-info.java` | NEW | `@ApplicationModule` (no explicit deps) |
| `keevo/backend/src/main/java/com/keevo/store/package-info.java` | NEW | `@ApplicationModule(allowedDependencies = {"identity"})` |
| `keevo/backend/src/main/java/com/keevo/subscription/package-info.java` | NEW | `@ApplicationModule(allowedDependencies = {"identity"})` |
| `keevo/backend/src/main/java/com/keevo/identity/package-info.java` | NEW | `@ApplicationModule` (no explicit deps) |
| `keevo/backend/src/main/java/com/keevo/shared/package-info.java` | NEW | `@ApplicationModule(type = Type.OPEN)` |
| `keevo/backend/src/test/java/com/keevo/architecture/ApplicationModulesBaselineTest.java` | NEW | Module detection + doc generation + verify() warning mode |
| `keevo/backend/src/test/java/com/keevo/architecture/KeevoArchUnitBaselineTest.java` | NEW | 3 hexagonal layering rules (warning mode) |
| `.github/workflows/backend-ci.yml` | UPDATE | Upload architecture-reports artifact (spring-modulith-docs/, violations) |

### Review Findings — code review (2026-06-19, bmad-code-review adversarial)

> Story la plus propre du lot : **AC1-AC6 tous PASS**, 0 Critical/High réel. Les tests sont **volontairement non-bloquants** (mode warning — AC2/AC6), enforcement reportée à Epic 12.2e / Story 12.6. Les findings ci-dessous sont des **améliorations pour la phase d'enforcement**, pas des bugs bloquants.

- [x] [Review][Defer] **Tests « warning mode » ne protègent contre aucune régression** — `verify()` et les règles ArchUnit n'assertent rien (catch + log). C'est l'intention NOW (AC2/AC6), mais à l'activation, convertir en **ratchet** : asserter le set de violations connues pour échouer sur toute NOUVELLE violation.
- [x] [Review][Defer] **`allowedDependencies` divergent des imports réels** — `identity` et `messaging` sont `@ApplicationModule` nus (allow nothing) alors qu'ils importent plusieurs modules ; `catalog` n'autorise pas `messaging` (importé) ; `store` n'autorise pas `subscription` (importé). C'est capturé comme baseline de violations (attendu), mais les déclarations devront être corrigées (cible vs réel) avant enforcement.
- [x] [Review][Defer] **`detectModulesAndGenerateDocumentation()` non protégé par try/catch** — si `ApplicationModules.of()`/`Documenter` lève (modèle invalide), le build échoue malgré l'intention warning-mode. Wrapper comme le test verify(). (3/3 GREEN actuellement.)
- [x] [Review][Defer] **Règles ArchUnit à affiner** — pattern `..adapter.out.persistence..` ne matche pas `shared.infrastructure.persistence.entity` (les 8 `@Entity`) ; ajouter `withImportOption(DO_NOT_INCLUDE_TESTS)`. À régler avant enforcement.
- [x] [Review][Defer] **CI `upload-artifact@v4` sans `if-no-files-found`** — si les tests ne tournent pas (échec compile), l'upload peut faire échouer le job. Ajouter `if-no-files-found: warn`. (Chemins `spring-modulith-docs/` vérifiés conformes.)
- [x] [Review][Defer] **`spring-modulith-api` en scope `compile` + `archunit-junit5` épinglé 1.3.0** — envisager `provided` (annotation non requise au runtime app) et laisser le BOM Modulith gérer la version d'ArchUnit (éviter le skew avec celle bundlée par Modulith 1.4).
