import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('sample',Path(__file__).parents[1]/'scripts/find-connection-sample.py')
sample=importlib.util.module_from_spec(spec);spec.loader.exec_module(sample)

def row(indices,chunk=-1):
    return {'chunkX':chunk,'chunkZ':0,'storages':[{'layer':0,'y':5,
        'palette':[{'legacyId':0},{'legacyId':102},{'legacyId':85}], 'indices':indices}]}

class SampleCoordinates(unittest.TestCase):
    def test_negative_world_coordinates(self):
        cells=[0]*4096;cells[8]=cells[264]=1
        found=sample.pair(row(cells))
        self.assertEqual(found['positions'],[[-16,88,0],[-15,88,0]])

    def test_does_not_wrap_across_storage_edge(self):
        cells=[0]*4096;cells[(15<<8)+8]=cells[8]=1
        self.assertIsNone(sample.pair(row(cells)))

    def test_different_unrelated_materials_are_not_a_pair(self):
        cells=[0]*4096;cells[8]=1;cells[264]=2
        self.assertIsNone(sample.pair(row(cells)))

if __name__=='__main__':unittest.main()
