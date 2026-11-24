// Demo geocoder: explicit searches only. For production traffic, use a private/contracted
// Photon endpoint: https://github.com/komoot/photon#demo-server
export const GEOCODER_URL = 'https://photon.komoot.io/api/';

function validPoint(point) {
  return Number.isFinite(point?.latitude) && Math.abs(point.latitude) <= 90
    && Number.isFinite(point?.longitude) && Math.abs(point.longitude) <= 180;
}

export async function searchPlaces(query, center, {signal, fetcher = fetch} = {}) {
  const text = query.trim();
  if (text.length < 2) return [];
  const url = new URL(GEOCODER_URL);
  url.searchParams.set('q', text);
  url.searchParams.set('limit', '5');
  if (validPoint({latitude: center?.lat, longitude: center?.lng})) {
    url.searchParams.set('lat', center.lat);
    url.searchParams.set('lon', center.lng);
  }
  const timeout = AbortSignal.timeout(10000);
  const response = await fetcher(url.href, {
    credentials: 'omit', signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
  });
  if (!response.ok) throw new Error('Không thể tìm địa điểm lúc này. Hãy thử lại hoặc chọn trên bản đồ.');
  const data = await response.json();
  if (!Array.isArray(data?.features)) throw new Error('Dữ liệu tìm địa điểm không hợp lệ.');
  return data.features.flatMap(feature => {
    const [longitude, latitude] = feature.geometry?.coordinates || [];
    const point = {latitude, longitude};
    if (feature.geometry?.type !== 'Point' || !validPoint(point)) return [];
    const p = feature.properties || {};
    const street = [p.housenumber, p.street].filter(Boolean).join(' ');
    const address = [...new Set([p.name, street, p.district, p.city, p.state, p.country].filter(v => typeof v === 'string' && v.trim()))].join(', ').slice(0, 180);
    return address ? [{address, ...point}] : [];
  }).slice(0, 5);
}

export function distanceKm(a, b) {
  const rad = n => n * Math.PI / 180;
  const h = Math.sin(rad(b.latitude - a.latitude) / 2) ** 2
    + Math.cos(rad(a.latitude)) * Math.cos(rad(b.latitude)) * Math.sin(rad(b.longitude - a.longitude) / 2) ** 2;
  return 6371 * 2 * Math.asin(Math.sqrt(Math.min(1, Math.max(0, h))));
}

export function nearbyByDistance(drivers, point) {
  if (!validPoint(point)) return [];
  return drivers.filter(d => validPoint(d.location))
    .map(d => ({...d, distanceKm: distanceKm(point, d.location)}))
    .sort((a, b) => a.distanceKm - b.distanceKm);
}
