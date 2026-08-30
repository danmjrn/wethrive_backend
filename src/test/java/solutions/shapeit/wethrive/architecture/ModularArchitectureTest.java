package solutions.shapeit.wethrive.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.ArchConfiguration;
import org.junit.jupiter.api.Test;

class ModularArchitectureTest {
    @Test
    void controllersDoNotAccessRepositoriesOrJpaEntitiesDirectly() {
        ArchConfiguration.get().setResolveMissingDependenciesFromClassPath(false);
        var classes = new ClassFileImporter().importPackages("solutions.shapeit.wethrive");
        noClasses().that().resideInAPackage("..controller..")
                .should().dependOnClassesThat().resideInAnyPackage("..repository..", "..entity..")
                .check(classes);
    }
}
