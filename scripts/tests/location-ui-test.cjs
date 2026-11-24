// Run this file with Playwright browser_run_code_unsafe's filename argument.
// Prerequisite: serve src/api-gateway/src/main/resources/static on localhost:8765.
// Only HTTP API/geocoding boundaries are stubbed; Leaflet and page code are real.
async (page) => {
  const testContext = await page.context().browser().newContext();
  page = await testContext.newPage();
  await page.unrouteAll({behavior: 'ignoreErrors'});
  page.setDefaultTimeout(10000);
  const check = (ok, message) => { if (!ok) throw new Error(message); };
  const passenger = '00000000-0000-0000-0000-000000000001', driver = '00000000-0000-0000-0000-000000000002';
  const pickup = {latitude: 21.0293, longitude: 105.8355, address: 'Văn Miếu, Hà Nội'};
  const destination = {latitude: 21.0242, longitude: 105.8576, address: 'Nhà hát Lớn, Hà Nội'};
  let booking, currentTrip = null, patches = [], searches = 0, delayQuote = false, releaseQuote, searchMode = 'normal';
  let nearbyRequests = 0, assignedLocationRequests = 0;
  const profile = {id: driver, fullName: 'Tài xế gần', status: 'AVAILABLE', location: {latitude: 21.0294, longitude: 105.8356}};
  await page.context().grantPermissions(['geolocation']);
  await page.context().setGeolocation({latitude: 21.03, longitude: 105.84, accuracy: 15});
  await page.addInitScript(({passenger, driver}) => {
    localStorage.clear();
    for (const [role, userId] of [['PASSENGER', passenger], ['DRIVER', driver]]) {
      localStorage.setItem(`di-session-${role}`, JSON.stringify({role, userId, accessToken: 'test-token', expiresAt: Date.now() + 3600000}));
    }
  }, {passenger, driver});
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url());
    const send = data => route.fulfill({json: data});
    if (url.host === 'photon.komoot.io') {
      searches++;
      check(!req.headers().authorization, 'Geocoding must not receive app bearer');
      if (searchMode === 'failure') return route.fulfill({status: 503, body: ''});
      const point = url.searchParams.get('q').includes('Văn') ? pickup : destination;
      return send({features: searchMode === 'empty' ? [] : [{type: 'Feature', geometry: {type: 'Point', coordinates: [point.longitude, point.latitude]}, properties: {name: point.address.split(',')[0], city: 'Hà Nội'}}]});
    }
    if (url.pathname.includes('/notifications/')) return send(currentTrip && url.pathname.includes('/driver/')
      ? [{id: 'note', eventType: 'DriverAssignmentConfirmed', tripId: 'trip-test', timestamp: '2026-11-24T00:00:00Z', recipientType: 'DRIVER'}] : []);
    if (url.pathname === '/api/drivers/nearby') { nearbyRequests++; return send([
      {id: 'far', fullName: 'Tài xế xa', location: {latitude: 21.04, longitude: 105.84}}, profile,
    ]); }
    if (url.pathname === `/api/drivers/${driver}/location`) {
      patches.push(req.postDataJSON()); profile.location = req.postDataJSON(); return send({location: profile.location});
    }
    if (url.pathname === `/api/drivers/${driver}`) { assignedLocationRequests++; return send(profile); }
    if (url.pathname === '/api/pricing/quote') {
      if (delayQuote) await new Promise(resolve => { releaseQuote = resolve; });
      return send({totalFare: 42000, estimatedDistanceKm: 3.2, estimatedDurationMin: 10, routePoints: [[21.0293, 105.8355], [21.0242, 105.8576]]});
    }
    if (url.pathname === '/api/trips' && req.method() === 'POST') {
      booking = req.postDataJSON();
      currentTrip = {tripId: 'trip-test', status: 'ASSIGNED', driverId: driver, passengerId: passenger, ...booking, estimatedFare: 42000};
      return send(currentTrip);
    }
    if (url.pathname === '/api/trips/trip-test') return send(currentTrip);
    throw new Error(`Unexpected API request: ${req.method()} ${url.pathname}`);
  });
  const search = async (kind, query, expected) => {
    await page.locator(`#${kind}-address`).fill(query);
    await page.locator(`#${kind}-search`).click();
    await page.locator(`#${kind}-results button`).filter({hasText: expected}).click();
  };
  await page.goto('http://127.0.0.1:8765/demo/rider.html', {waitUntil: 'domcontentloaded'});
  check(await page.locator('#pickup-search').count() === 1, 'Passenger must have pickup search');
  await search('pickup', 'Văn Miếu', 'Văn Miếu');
  await page.waitForFunction(() => document.querySelector('#nearest-driver').textContent.includes('Tài xế gần'));
  check(await page.locator('.nearest-car').count() === 1, 'Exactly one nearest driver highlighted');
  delayQuote = true;
  const quoteRequested = page.waitForRequest('**/api/pricing/quote');
  await search('destination', 'Nhà hát', 'Nhà hát');
  await quoteRequested;
  await page.locator('#destination-address').fill('Địa chỉ chưa chọn');
  if (releaseQuote) releaseQuote();
  delayQuote = false;
  check(await page.locator('#book').isDisabled(), 'Editing destination must invalidate booking even with a pending quote');
  await page.locator('#destination-search').click();
  await page.locator('#destination-results button').click();
  await page.waitForFunction(() => !document.querySelector('#book').disabled);
  await page.locator('#locate-me').click();
  await page.waitForFunction(() => document.querySelector('#pickup-address').value.startsWith('Vị trí hiện tại'));
  check(await page.locator('.pin-me').count() === 1, 'Customer GPS marker shown separately');
  await search('pickup', 'Văn Miếu', 'Văn Miếu');
  await page.waitForFunction(() => !document.querySelector('#book').disabled);
  await page.locator('#book').click();
  await page.waitForFunction(() => document.querySelector('#plan').hidden);
  check(booking.pickup.latitude === pickup.latitude && booking.destination.longitude === destination.longitude, 'Booking uses selected coordinates');
  const nearbyBefore = nearbyRequests;
  currentTrip.status = 'PAID';
  await page.waitForFunction(() => !document.querySelector('#new-trip').hidden);
  check(assignedLocationRequests === 0, 'No real-time assigned driver position requests');
  check(nearbyBefore === nearbyRequests, 'No background nearby position refresh');
  await page.locator('#new-trip').click();
  await page.locator('#pickup-address').fill('Văn Miếu');
  searchMode = 'empty'; await page.locator('#pickup-search').click();
  await page.waitForFunction(() => document.querySelector('#pickup-search-status').textContent.includes('Không tìm thấy'));
  searchMode = 'failure'; await page.locator('#pickup-search').click();
  await page.waitForFunction(() => document.querySelector('#pickup-search-status').textContent.includes('Không thể'));
  searchMode = 'normal';
  await page.context().clearPermissions();
  await page.evaluate(() => { navigator.geolocation.getCurrentPosition = (_, reject) => reject({code: 1}); });
  await page.locator('#locate-me').click();
  await page.getByText('Bạn chưa cho phép truy cập vị trí.', {exact: false}).waitFor();
  await page.setViewportSize({width: 390, height: 844});
  check(await page.locator('#pickup-search').isVisible(), 'Mobile search accessible');
  check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'No mobile horizontal overflow');
  await search('pickup', 'Văn Miếu', 'Văn Miếu');
  check(await page.evaluate(() => document.querySelector('[title="Điểm đón khách hàng"]').getBoundingClientRect().bottom
    < document.querySelector('#plan').getBoundingClientRect().top), 'Selected pickup must be visible above mobile sheet');

  currentTrip.status = 'ASSIGNED';
  await page.setViewportSize({width: 1440, height: 960});
  await page.goto('http://127.0.0.1:8765/demo/driver.html', {waitUntil: 'domcontentloaded'});
  await page.getByText('Đến điểm đón hành khách', {exact: true}).waitFor();
  await page.locator('.place-preview summary').click();
  await search('pickup', 'Văn Miếu', 'Văn Miếu');
  await search('destination', 'Nhà hát', 'Nhà hát');
  check(patches.length === 0, 'Driver preview search must not report a new driver location');
  check(currentTrip.pickup.latitude === pickup.latitude, 'Preview leaves assigned trip intact');
  check(await page.locator('.leaflet-marker-icon[title="Khách hàng · điểm đón"]').count() === 1, 'Assigned customer pickup remains visible');
  await page.context().grantPermissions(['geolocation']);
  await page.locator('#locate-me').click();
  await page.waitForFunction(() => !document.querySelector('#locate-me').disabled);
  check(patches.length === 1 && patches[0].latitude === 21.03, 'Driver GPS sent through own location API');
  check((await page.locator('#customer-location').textContent()).includes('đường chim bay'), 'Driver sees pickup distance with correct meaning');
  await page.setViewportSize({width: 390, height: 844});
  check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'No driver mobile overflow');
  await page.locator('#locate-me').click();
  await page.waitForFunction(() => !document.querySelector('#locate-me').disabled);
  check(await page.evaluate(() => document.querySelector('[title="Vị trí của bạn"]').getBoundingClientRect().bottom
    < document.querySelector('.sheet').getBoundingClientRect().top), 'Driver GPS visible above mobile sheet');
  await page.goto('about:blank');
  await page.unrouteAll({behavior: 'ignoreErrors'});
  await testContext.close();
  return {passed: true, searches, locationReports: patches.length, checked: ['search both pages', 'selected booking coordinates', 'pending quote invalidation', 'nearest driver', 'no real-time driver tracking', 'GPS success and denial', 'empty/failed geocoder', 'driver preview isolation', 'mobile layout']};
}
