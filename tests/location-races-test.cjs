// Run using Playwright browser_run_code_unsafe filename; same static server as location-ui-test.cjs.
async (page) => {
  const testContext = await page.context().browser().newContext();
  page = await testContext.newPage();
  await page.goto('about:blank');
  await page.unrouteAll({behavior: 'ignoreErrors'});
  page.setDefaultTimeout(10000);
  await page.setViewportSize({width: 1440, height: 960});
  const failures = [], check = (ok, message) => { if (!ok) failures.push(message); };
  let releaseNearby, releaseBooking, delayNearby = false, trip = null;
  const pickup = {address: 'Hồ Hoàn Kiếm', latitude: 21.0287, longitude: 105.8524};
  const destination = {address: 'Nhà hát Lớn', latitude: 21.0242, longitude: 105.8576};
  await page.addInitScript(() => {
    localStorage.clear();
    localStorage.setItem('di-session-PASSENGER', JSON.stringify({role:'PASSENGER', userId:'passenger', accessToken:'test', expiresAt:Date.now()+3600000}));
    navigator.geolocation.getCurrentPosition = success => { window.deliverGPS = () => success({coords:{latitude:21.05,longitude:105.87,accuracy:10}}); };
  });
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url());
    if (url.pathname === '/api/drivers/nearby') {
      if (delayNearby) await new Promise(resolve => { releaseNearby = resolve; });
      return route.fulfill({json:[{id:'near',fullName:'Xe điểm đón cũ',location:{latitude:21.0288,longitude:105.8525}}]});
    }
    if (url.pathname === '/api/pricing/quote') return route.fulfill({json:{totalFare:42000,estimatedDistanceKm:3,estimatedDurationMin:10}});
    if (url.pathname.includes('/notifications/')) return route.fulfill({json:[]});
    if (url.pathname === '/api/trips' && req.method() === 'POST') {
      trip = {tripId:'race-trip',status:'SEARCHING',...req.postDataJSON()};
      await new Promise(resolve => { releaseBooking = resolve; });
      return route.fulfill({json:trip});
    }
    if (url.pathname === '/api/trips/race-trip') return route.fulfill({json:trip});
    throw Error(`Unexpected URL ${url.href}`);
  });
  await page.goto('http://127.0.0.1:8765/demo/rider.html', {waitUntil:'domcontentloaded'});
  await page.locator('[data-place="0"]').click();
  await page.waitForFunction(() => document.querySelector('#nearest-driver').textContent.includes('Xe điểm đón cũ'));
  // Map/chip changes do not emit an input event: stale nearest values must still be removed.
  await page.locator('[data-pick="pickup"]').click();
  delayNearby = true;
  const nearbyRequested = page.waitForRequest('**/api/drivers/nearby?**');
  await page.locator('[data-place="1"]').click();
  await nearbyRequested;
  check(!(await page.locator('#nearest-driver').textContent()).includes('Xe điểm đón cũ'), 'Old nearest-driver details remain while new pickup request is pending');
  check(await page.locator('.nearest-car').count() === 0, 'Old driver pin remains while new pickup request is pending');
  releaseNearby(); delayNearby = false;
  // GPS request started first, explicit selection happened later.
  await page.locator('#locate-me').click();
  await page.locator('[data-pick="pickup"]').click();
  await page.locator('[data-place="0"]').click();
  await page.evaluate(() => window.deliverGPS());
  await page.waitForFunction(() => !document.querySelector('#locate-me').disabled);
  check(await page.locator('#pickup-address').inputValue() === 'Hồ Hoàn Kiếm', 'Late GPS overwrote newer explicit pickup');
  // Book original coordinates while the HTTP mutation is deliberately pending.
  await page.locator('[data-pick="pickup"]').click(); await page.locator('[data-place="0"]').click();
  await page.locator('[data-pick="destination"]').click(); await page.locator('[data-place="1"]').click();
  await page.waitForFunction(() => !document.querySelector('#book').disabled);
  await page.locator('#book').click();
  check(await page.locator('.leaflet-marker-draggable').count() === 0, 'Pins remain draggable while booking is pending');
  releaseBooking();
  await page.waitForFunction(() => document.querySelector('#plan').hidden);
  // Start a fresh page on mobile; a single pickup must be in exposed map space.
  await page.setViewportSize({width:390,height:844});
  await page.goto('http://127.0.0.1:8765/demo/rider.html', {waitUntil:'domcontentloaded'});
  await page.locator('[data-place="0"]').click();
  await page.waitForTimeout(400); // Leaflet pan animation must finish before checking geometry.
  const bounds = await page.evaluate(() => ({marker:document.querySelector('[title="Điểm đón khách hàng"]').getBoundingClientRect().bottom,
    sheet:document.querySelector('#plan').getBoundingClientRect().top}));
  check(bounds.marker < bounds.sheet, `Mobile pickup hidden by sheet: marker bottom ${bounds.marker}, sheet top ${bounds.sheet}`);
  await page.setViewportSize({width:768,height:1024});
  await page.goto('http://127.0.0.1:8765/demo/rider.html', {waitUntil:'domcontentloaded'});
  await page.locator('[data-place="0"]').click();
  await page.waitForTimeout(400);
  const tabletBounds = await page.evaluate(() => ({marker:document.querySelector('[title="Điểm đón khách hàng"]').getBoundingClientRect().left,
    sheet:document.querySelector('#plan').getBoundingClientRect().right}));
  check(tabletBounds.marker > tabletBounds.sheet, `Tablet pickup hidden by sheet: marker left ${tabletBounds.marker}, sheet right ${tabletBounds.sheet}`);
  await page.goto('about:blank');
  await page.unrouteAll({behavior:'ignoreErrors'});
  await testContext.close();
  if (failures.length) throw Error(failures.join('; '));
  return {passed:true,checked:['nearest cleared on pickup change','late GPS preserves explicit pickup','booking locks draggable pins','mobile and tablet exposed map centering']};
}
