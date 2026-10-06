import {readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
const path=resolve(process.env.ROUTEBOOK_FIXTURES??process.env.ROUTEBOOK_FIXTURES_PATH??'contract/fixtures.json');
const bytes=await readFile(path),f=JSON.parse(bytes);assert.equal(f.synthetic,true);assert.equal(f.version,1);assert.equal(f.cases.length,9);
assert.equal(new Set(f.cases.map(c=>c.name)).size,9);assert.ok(f.device_id);assert.equal(f.ui_range_acceptance.max_concurrent_day_requests,2);
console.log('PASS shared contract fixtures: 9 unique synthetic cases; sha256 '+createHash('sha256').update(bytes).digest('hex'));
