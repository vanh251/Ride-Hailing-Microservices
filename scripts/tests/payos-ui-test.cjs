// Playwright MCP browser_run_code_unsafe filename. Serve static assets on localhost:8765.
async (page) => {
  const context = await page.context().browser().newContext();
  page = await context.newPage();
  page.setDefaultTimeout(10000);
  const check = (ok, message) => { if (!ok) throw Error(message); };
  const passenger = '00000000-0000-4000-8000-000000000001', driver = '00000000-0000-4000-8000-000000000002';
  const tripId = '00000000-0000-4000-8000-000000000003';
  let tripStatus = 'IN_PROGRESS', paymentStatus = 'PENDING', posts = 0, failCreate = true;
  let delayRead = false, releaseRead, redirect = false;
  const trip = () => ({tripId,status:tripStatus,paymentProvider:'PAYOS',passengerId:passenger,driverId:driver,currency:'VND',estimatedFare:40000,finalFare:42000,
    pickup:{latitude:21.02,longitude:105.84,address:'Điểm đón'},destination:{latitude:21.03,longitude:105.85,address:'Điểm đến'}});
  const payment = () => ({tripId,status:paymentStatus,provider:'PAYOS',amount:42000,currency:'VND'});
  await page.addInitScript(({passenger,driver,tripId}) => {
    for (const [role,userId] of [['PASSENGER',passenger],['DRIVER',driver]])
      localStorage.setItem(`di-session-${role}`, JSON.stringify({role,userId,accessToken:'test-token',expiresAt:Date.now()+3600000}));
    localStorage.setItem(`di-trip-${passenger}`,tripId);
  }, {passenger,driver,tripId});
  await page.route('**/api/**', async route => {
    const req=route.request(), url=new URL(req.url());
    const send=data=>route.fulfill({json:data});
    if (url.pathname===`/api/payments/${tripId}/checkout`) {
      if (req.method()==='POST') {
        posts++;
        check(!req.postData(),'Checkout must never accept frontend amount/passenger data');
        if (failCreate) return route.fulfill({status:503,json:{code:'PAYOS_NOT_CONFIGURED',message:'Not configured'}});
        if (redirect) return send({...payment(),checkoutUrl:'https://pay.payos.vn/web/local-fixture'});
        paymentStatus='SUCCESS'; return send(payment());
      }
      const result=payment();
      if (delayRead) {delayRead=false; await new Promise(resolve=>{releaseRead=resolve;});}
      return send(result);
    }
    if (url.pathname===`/api/trips/${tripId}`) return send(trip());
    if (url.pathname===`/api/drivers/${driver}`) return send({id:driver,fullName:'Tài xế thử',status:'AVAILABLE',location:{latitude:21.02,longitude:105.84}});
    if (url.pathname===`/api/notifications/driver/${driver}`) return send([{id:'n',eventType:'DriverAssignmentConfirmed',tripId,timestamp:'2026-11-24T00:00:00Z'}]);
    if (url.pathname.includes('/notifications/')) return send([]);
    if (url.pathname==='/api/pricing/quote') return send({totalFare:42000,estimatedDistanceKm:3,estimatedDurationMin:10});
    throw Error(`Unexpected ${req.method()} ${url.pathname}`);
  });
  await page.route('https://pay.payos.vn/**',route=>route.fulfill({contentType:'text/html',body:'<h1>Hosted checkout fixture</h1>'}));
  try {
    await page.goto('http://127.0.0.1:8765/demo/rider.html', {waitUntil:'domcontentloaded'});
    await page.waitForFunction(()=>document.querySelector('#plan').hidden);
    check(await page.locator('#payment-panel').isHidden(),'No prepayment panel while trip in progress');
    tripStatus='COMPLETED';
    await page.locator('#pay-checkout').waitFor({state:'visible'});
    await page.locator('#pay-checkout').click();
    await page.getByText('Thanh toán trực tuyến tạm thời chưa sẵn sàng.',{exact:false}).first().waitFor();
    check(await page.locator('#pay-checkout').isEnabled(),'503 keeps retry available');
    check(await page.locator('#payment-title').textContent()==='Chờ thanh toán','Missing configuration never pretends success');

    // A forged return status cannot mark the trip paid.
    await page.goto(`http://127.0.0.1:8765/demo/rider.html?tripId=${tripId}&status=PAID&code=00`,{waitUntil:'domcontentloaded'});
    await page.locator('#pay-checkout').waitFor({state:'visible'});
    check(await page.locator('#payment-title').textContent()==='Chờ thanh toán','Return URL status ignored until backend confirms');

    // A pending read that began before checkout succeeds must not undo success.
    failCreate=false; delayRead=true;
    const heldRead=page.waitForRequest(`**/api/payments/${tripId}/checkout`);
    await page.locator('#payment-refresh').click();
    await heldRead;
    await page.locator('#pay-checkout').click();
    await page.waitForFunction(()=>document.querySelector('#payment-title').textContent==='Đã thanh toán');
    const delivered=page.waitForResponse(response=>response.url().endsWith(`/api/payments/${tripId}/checkout`) && response.request().method()==='GET');
    releaseRead(); await delivered;
    await page.waitForTimeout(100);
    check(await page.locator('#pay-checkout').isHidden(),'Late pending GET must not undo confirmed payment success');

    // Opening the real provider URL is stubbed, never transfers money.
    paymentStatus='PENDING'; redirect=true;
    await page.goto('http://127.0.0.1:8765/demo/rider.html',{waitUntil:'domcontentloaded'});
    await page.locator('#pay-checkout').waitFor({state:'visible'});
    await page.locator('#pay-checkout').click();
    await page.waitForURL('https://pay.payos.vn/web/local-fixture');
    await page.getByRole('heading',{name:'Hosted checkout fixture'}).waitFor();

    await page.goto('http://127.0.0.1:8765/demo/driver.html',{waitUntil:'domcontentloaded'});
    await page.locator('#payment-panel').waitFor({state:'visible'});
    check(await page.locator('#pay-checkout').isHidden(),'Driver cannot initiate customer checkout');
    const postsBefore=posts;
    paymentStatus='SUCCESS';
    await page.locator('#payment-refresh').click();
    await page.waitForFunction(()=>document.querySelector('#payment-title').textContent==='Đã thanh toán');
    check(posts===postsBefore,'Driver only reads status');
    return {passed:true,posts,checked:['post-trip only','missing config','ignore forged return status','stale read isolation','hosted checkout redirect','driver read-only payment state']};
  } finally {await context.close();}
}
