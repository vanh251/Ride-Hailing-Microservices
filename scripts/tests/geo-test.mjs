import {test} from 'node:test';
import assert from 'node:assert/strict';
import * as geo from '../../src/api-gateway/src/main/resources/static/demo/geo.mjs';

test('search maps GeoJSON longitude/latitude and street address to booking points', async () => {
  let request;
  const places = await geo.searchPlaces('  Văn Miếu  ', {lat: 21, lng: 105}, {fetcher: async (url, options) => {
    request = {url: new URL(url), options};
    return Response.json({features: [
      {geometry: {type: 'Point', coordinates: [105.8355, 21.0293]}, properties: {name: 'Văn Miếu', housenumber: '58', street: 'Quốc Tử Giám', city: 'Hà Nội', country: 'Việt Nam'}},
      {geometry: {type: 'Point', coordinates: [0, 0]}, properties: {name: 'Zero'}},
      {geometry: {type: 'Point', coordinates: [200, 10]}, properties: {name: 'Bad'}},
      {geometry: {type: 'Point', coordinates: [null, '']}, properties: {name: 'Empty'}},
    ]});
  }});
  assert.deepEqual(places, [
    {address: 'Văn Miếu, 58 Quốc Tử Giám, Hà Nội, Việt Nam', latitude: 21.0293, longitude: 105.8355},
    {address: 'Zero', latitude: 0, longitude: 0},
  ]);
  assert.equal(request.url.searchParams.get('q'), 'Văn Miếu');
  assert.equal(request.url.searchParams.get('lat'), '21');
  assert.equal(request.url.searchParams.get('lon'), '105');
  assert.equal(request.options.credentials, 'omit');
  assert.equal(request.options.headers?.Authorization, undefined);
});

test('search skips short queries, reports service errors, validates response shape', async () => {
  assert.deepEqual(await geo.searchPlaces(' ', null, {fetcher: () => { throw Error('must not request'); }}), []);
  await assert.rejects(geo.searchPlaces('Hà Nội', null, {fetcher: async () => new Response('', {status: 429})}), /tìm địa điểm/i);
  await assert.rejects(geo.searchPlaces('Hà Nội', null, {fetcher: async () => Response.json({unexpected: []})}), /dữ liệu/i);
  assert.deepEqual(await geo.searchPlaces('Hà Nội', null, {fetcher: async () => Response.json({features: []})}), []);
});

test('nearest driver is computed from selected pickup, not API ordering; missing positions excluded', () => {
  const list = [
    {id: 'far', location: {latitude: 0, longitude: .02}},
    {id: 'missing', location: null},
    {id: 'near', location: {latitude: 0, longitude: .01}},
    {id: 'invalid', location: {latitude: null, longitude: 0}},
  ];
  const sorted = geo.nearbyByDistance(list, {latitude: 0, longitude: 0});
  assert.deepEqual(sorted.map(d => d.id), ['near', 'far']);
  assert.ok(Math.abs(sorted[0].distanceKm - 1.11195) < .001);
  assert.equal(list[0].distanceKm, undefined);
  assert.equal(geo.distanceKm({latitude: 0, longitude: 0}, {latitude: 0, longitude: 0}), 0);
});
