// Serve src/api-gateway/src/main/resources/static on localhost:8766, then run with Playwright.
async (page) => {
  const context = await page.context().browser().newContext();
  try {
    const passenger = 'passenger-test', driver = 'driver-test';
    let trip = {tripId: 'acceptance-test', status: 'ASSIGNED', passengerId: passenger, driverId: driver,
      pickup: {latitude: 21.028, longitude: 105.85, address: 'Điểm đón'},
      destination: {latitude: 21.03, longitude: 105.86, address: 'Điểm đến'}, estimatedFare: 42000};
    await context.addInitScript(({passenger, driver}) => {
      for (const [role, userId] of [['PASSENGER', passenger], ['DRIVER', driver]]) {
        localStorage.setItem(`di-session-${role}`, JSON.stringify({role, userId, accessToken: 'test', expiresAt: Date.now() + 3600000}));
      }
      localStorage.setItem(`di-trip-${passenger}`, 'acceptance-test');
    }, {passenger, driver});
    await context.route('**/api/**', async route => {
      const path = new URL(route.request().url()).pathname;
      if (path === '/api/trips/acceptance-test/accept') {
        trip = {...trip, acceptedAt: '2026-11-27T01:00:00Z'};
        return route.fulfill({json: trip});
      }
      if (path === '/api/trips/acceptance-test') return route.fulfill({json: trip});
      if (path.includes('/notifications/')) return route.fulfill({json: [{id: 'note', eventType: 'DriverAssignmentConfirmed',
        tripId: trip.tripId, recipientType: path.includes('/driver/') ? 'DRIVER' : 'PASSENGER', timestamp: '2026-11-27T00:00:00Z'}]});
      if (path === `/api/drivers/${driver}`) return route.fulfill({json: {id: driver, fullName: 'Tài xế', status: 'BUSY'}});
      if (path === '/api/pricing/quote') return route.fulfill({json: {totalFare: 42000, routePoints: [[21.028, 105.85], [21.03, 105.86]]}});
      throw new Error(`Unexpected request: ${path}`);
    });
    const rider = await context.newPage(), driverPage = await context.newPage();
    await rider.goto('http://127.0.0.1:8766/demo/rider.html');
    await rider.locator('#status-title').filter({hasText: 'Chờ tài xế xác nhận'}).waitFor();
    await rider.locator('#timeline li.current').filter({hasText: 'Chờ tài xế xác nhận'}).waitFor();
    await rider.waitForTimeout(3500);
    if (await rider.locator('#status-title').textContent() !== 'Chờ tài xế xác nhận') throw new Error('Polling skipped driver acceptance');
    await driverPage.goto('http://127.0.0.1:8766/demo/driver.html');
    await driverPage.locator('#accept').waitFor({state: 'visible'});
    if (await driverPage.locator('#start').isVisible()) throw new Error('Start visible before acceptance');
    await driverPage.locator('#accept').click();
    await driverPage.locator('#start').waitFor({state: 'visible'});
    await rider.locator('#status-title').filter({hasText: 'Tài xế đang đến'}).waitFor();
    await rider.reload();
    await rider.locator('#timeline li.current').filter({hasText: 'Tài xế đang đến'}).waitFor();
    await driverPage.reload();
    await driverPage.locator('#start').waitFor({state: 'visible'});
    if (await driverPage.locator('#accept').isVisible()) throw new Error('Reload lost server acceptance');
    return 'PASS: passenger waits through polling, driver accepts, both pages preserve acceptance after reload';
  } finally { await context.close(); }
}
