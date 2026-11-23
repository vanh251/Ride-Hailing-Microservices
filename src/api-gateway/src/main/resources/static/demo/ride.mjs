// Browser side shared by login.html, rider.html and driver.html: session per role, API calls,
// popups and the map. The gateway enforces the roles on every API call; the guard here only keeps
// each account on its own page.
import {request, validSession} from './client.mjs';
import {pageForRole} from './ride-core.mjs';

export const $ = id => document.getElementById(id);
export const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));

// One session per role, so a passenger tab and a driver tab can run side by side in one browser
const sessionKey = role => `di-session-${role}`;

export function loadSession(role) {
  try {
    const session = JSON.parse(localStorage.getItem(sessionKey(role)) || 'null');
    return validSession(session, role) ? session : null;
  } catch {
    return null;
  }
}

export function saveSession(session) {
  try { localStorage.setItem(sessionKey(session.role), JSON.stringify(session)); } catch { /* private window: session lasts for this page only */ }
}

export function logout(role) {
  try { localStorage.removeItem(sessionKey(role)); } catch { /* nothing stored */ }
  location.href = `login.html?role=${role.toLowerCase()}`;
}

// The signed-in session of the page's role, or a redirect to the login page
export function requireRole(role) {
  const session = loadSession(role);
  if (!session) location.replace(`login.html?role=${role.toLowerCase()}`);
  return session;
}

export function goHome(role) {
  location.href = pageForRole(role) || 'login.html';
}

export async function api(path, options = {}, session = null) {
  try {
    return (await request(path, {...options, ...(session ? {token: session.accessToken} : {})})).data;
  } catch (error) {
    if (error.status === 401 && session) logout(session.role);
    if (error.status === 403) error.message = 'Tài khoản của bạn không có quyền thực hiện thao tác này.';
    if (error.name === 'TimeoutError') error.message = 'Máy chủ phản hồi quá lâu. Vui lòng thử lại.';
    throw error;
  }
}

const TOAST_ICONS = {info: '#i-bell', success: '#i-check', warn: '#i-alert', error: '#i-alert'};

export function toast(title, body = '', kind = 'info') {
  const item = document.createElement('div');
  item.className = `toast ${kind}`;
  item.setAttribute('role', kind === 'error' ? 'alert' : 'status');
  item.innerHTML = `<svg class="icon"><use href="${TOAST_ICONS[kind]}"/></svg><div><strong>${esc(title)}</strong>${body ? `<p>${esc(body)}</p>` : ''}</div><button class="toast-close" aria-label="Đóng thông báo"><svg class="icon"><use href="#i-close"/></svg></button>`;
  const close = () => { item.classList.add('leaving'); setTimeout(() => item.remove(), 200); };
  item.querySelector('button').onclick = close;
  $('toasts').prepend(item);
  setTimeout(close, kind === 'error' ? 8000 : 5500);
}

export function failure(error) {
  toast('Không thực hiện được', error.message, 'error');
}

// shortcut: tiles come from openstreetmap.org over the internet (fine for a demo, not for production traffic);
// host a tile server or use a keyed provider before real use
export const HANOI = [21.0285, 105.8542];

export function makeMap(element, center = HANOI, zoom = 15, interactive = true) {
  const map = L.map(element, {zoomControl: false, dragging: interactive, scrollWheelZoom: interactive, doubleClickZoom: interactive,
    touchZoom: interactive, keyboard: interactive}).setView(center, zoom);
  if (interactive) L.control.zoom({position: 'bottomright'}).addTo(map);
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
  }).addTo(map);
  return map;
}

export function pinIcon(kind) {
  const html = kind === 'car'
    ? '<span class="pin-car"><svg class="icon"><use href="#i-car"/></svg></span>'
    : `<span class="pin ${kind}"><span></span></span>`;
  return L.divIcon({className: 'pin-wrap', html, iconSize: kind === 'car' ? [34, 34] : [28, 36], iconAnchor: kind === 'car' ? [17, 17] : [14, 34]});
}

export function placeLabel(latlng) {
  return `Vị trí đã ghim (${latlng.lat.toFixed(4)}, ${latlng.lng.toFixed(4)})`;
}

// The signed-in person in the top bar, with sign-out
export function renderAccount(session, roleLabel) {
  $('account').innerHTML = `<span class="role-chip">${esc(roleLabel)}</span><span class="account-email">${esc(session.email || '')}</span>`;
  $('logout').onclick = () => logout(session.role);
}
