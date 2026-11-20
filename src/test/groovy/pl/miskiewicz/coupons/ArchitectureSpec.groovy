package pl.miskiewicz.coupons

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import spock.lang.Shared
import spock.lang.Specification

class ArchitectureSpec extends Specification {

    @Shared
    JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages('pl.miskiewicz.coupons')

    def "the domain depends on neither Spring nor any adapter"() {
        expect:
        noClasses().that().resideInAPackage('..coupon.domain..')
                .should().dependOnClassesThat().resideInAnyPackage(
                        'org.springframework..', 'io.micrometer..',
                        '..coupon.application..', '..coupon.persistence..', '..coupon.web..', '..geo..', 'pl.miskiewicz.coupons.web..')
                .check(classes)
    }

    def "web adapters never reach persistence directly"() {
        expect:
        noClasses().that().resideInAnyPackage('..coupon.web..', 'pl.miskiewicz.coupons.web..')
                .should().dependOnClassesThat().resideInAPackage('..coupon.persistence..')
                .check(classes)
    }

    def "application services depend on ports, never on adapters"() {
        expect:
        noClasses().that().resideInAPackage('..coupon.application..')
                .should().dependOnClassesThat().resideInAnyPackage(
                        '..coupon.persistence..', '..coupon.web..', '..geo..', 'pl.miskiewicz.coupons.web..')
                .check(classes)
    }

    def "adapters do not depend on each other"() {
        expect:
        noClasses().that().resideInAnyPackage('..coupon.persistence..', '..geo..')
                .should().dependOnClassesThat().resideInAnyPackage('..coupon.web..', 'pl.miskiewicz.coupons.web..')
                .check(classes)
    }
}
