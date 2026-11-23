export async function request(path, {method = 'GET', token, body, correlationId = crypto.randomUUID()} = {}, fetcher = fetch) {
  const headers = {'Content-Type':'application/json', 'X-Correlation-ID':correlationId};
  if (token) headers.Authorization = `Bearer ${token}`;
  let response, text;
  try {
    response = await fetcher(path, {
      method, headers, signal:AbortSignal.timeout(12000),
      ...(body === undefined ? {} : {body:JSON.stringify(body)})
    });
    text = await response.text();
  } catch(error) {
    throw Object.assign(error, {correlationId});
  }
  let data;
  try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  const trace = response.headers.get('X-Correlation-ID') || data?.correlationId || correlationId;
  if (!response.ok) {
    throw Object.assign(new Error(data?.message || `HTTP ${response.status}: không thể hoàn tất yêu cầu.`), {
      status:response.status, code:data?.code, correlationId:trace
    });
  }
  return {data, status:response.status, correlationId:trace};
}

export function validSession(session, role) {
  return Boolean(session?.accessToken && session.userId && session.role === role && session.expiresAt > Date.now());
}

export function canReplaceSession(current, next, hasUnfinishedWork) {
  return !hasUnfinishedWork || Boolean(current?.userId && current.userId === next.userId);
}

export function bookingUncertain(error) {
  if(error.status === 503 && error.code === 'DOWNSTREAM_UNAVAILABLE') return false;
  return !error.status || error.status >= 500 || error.status === 408;
}

export function parseRoute(values) {
  return Object.fromEntries(['pickup','destination'].map(key=>{
    const point=values[key];
    if(!point?.address?.trim() || point.address.trim().length>180)throw new Error('Nhập địa chỉ hợp lệ cho cả hai điểm.');
    const coordinates={};
    for(const [field,limit] of [['latitude',90],['longitude',180]]){
      const text=String(point[field] ?? '').trim(),number=Number(text);
      if(!text || !Number.isFinite(number) || Math.abs(number)>limit)throw new Error('Nhập đầy đủ tọa độ hợp lệ: vĩ độ ±90, kinh độ ±180.');
      coordinates[field]=number;
    }
    return [key,{address:point.address.trim(),...coordinates}];
  }));
}

export function driverActions(trip, driverId) {
  if (!driverId || !trip || trip.driverId !== driverId) return [];
  if (trip.status === 'ASSIGNED') return ['accept','start'];
  return trip.status === 'IN_PROGRESS' ? ['complete'] : [];
}

export function bookPair(slots, payload, book) {
  return Promise.allSettled(slots.map(slot => book(slot, payload)));
}

export function raceOutcome(trips, driver) {
  if (trips.length !== 2 || trips.some(t => !t) || !driver) return 'waiting';
  const owned = trips.filter(t => t.driverId === driver.id && ['ASSIGNED','IN_PROGRESS'].includes(t.status));
  if (owned.length > 1) return 'conflict';
  const other = trips.find(t => !owned.includes(t));
  return owned.length === 1 && driver.status === 'BUSY' && !other?.driverId
    && ['SEARCHING','CANCELLED'].includes(other?.status) ? 'exclusive' : 'waiting';
}
