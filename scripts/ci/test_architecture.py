"""Repository boundary contracts: direct dependencies and production source references."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
SERVICES = ('order', 'document', 'notification')
SHARED = ('event-contracts', 'common-observability')


def dependencies(module):
    tree = ET.parse(module / 'pom.xml')
    return [(d.findtext('m:groupId', namespaces=NS), d.findtext('m:artifactId', namespaces=NS))
            for d in tree.findall('./m:dependencies/m:dependency', NS)
            if d.findtext('m:scope', namespaces=NS) != 'test']


class ArchitectureContracts(unittest.TestCase):
    def test_service_modules_depend_only_on_shared_platform_jars(self):
        for service in SERVICES:
            module = ROOT / 'services' / (service + '-service')
            for group, artifact in dependencies(module):
                if group == 'com.synechisveltiosi':
                    self.assertIn(artifact, SHARED, f'{module.name} imports deployable service {artifact}')

    def test_production_sources_do_not_reference_another_services_packages(self):
        for service in SERVICES:
            for source in (ROOT / 'services' / (service + '-service') / 'src/main/java').rglob('*.java'):
                for other in set(SERVICES) - {service}:
                    self.assertNotRegex(source.read_text(),
                        re.escape('com.synechisveltiosi.platform.' + other) + r'\.', str(source))

    def test_shared_jars_remain_framework_and_service_independent(self):
        allowed = {'event-contracts': set(), 'common-observability': {('org.slf4j', 'slf4j-api')}}
        for shared in SHARED:
            module = ROOT / 'shared' / shared
            self.assertLessEqual(set(dependencies(module)), allowed[shared])
            for source in (module / 'src/main/java').rglob('*.java'):
                self.assertNotRegex(source.read_text(),
                    r'org\.springframework\.|jakarta\.persistence\.|com\.synechisveltiosi\.platform\.(order|document|notification)\.')

    def test_document_service_has_no_database_or_migration_adapter(self):
        module = ROOT / 'services/document-service'
        for group, artifact in dependencies(module):
            self.assertNotRegex(group + ':' + artifact, r'(?i)jdbc|postgres|hibernate|flyway|liquibase|data-jpa|cloud\.sql')
        for source in (module / 'src/main/java').rglob('*.java'):
            self.assertNotRegex(source.read_text(),
                r'java\.sql\.|javax\.sql\.|jakarta\.persistence\.|org\.springframework\.(jdbc|data)\.')
        self.assertFalse((module / 'src/main/resources/db/migration').exists())


if __name__ == '__main__':
    unittest.main()
