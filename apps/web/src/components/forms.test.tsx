import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ChangePinForm } from './change-pin-form';
import { LoginForm } from './login-form';

const replace = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace, refresh: vi.fn(), back: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
}));

afterEach(() => {
  vi.unstubAllGlobals();
  replace.mockClear();
});

const wrap = (ui: React.ReactElement) =>
  render(<QueryClientProvider client={new QueryClient()}>{ui}</QueryClientProvider>);

const reply = (status: number, body?: unknown) =>
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response(body === undefined ? null : JSON.stringify(body), { status })),
  );

describe('LoginForm', () => {
  it('offers Microsoft sign-in as a plain link to the API', () => {
    wrap(<LoginForm />);
    expect(screen.getByRole('link', { name: 'Đăng nhập bằng SSO' })).toHaveAttribute(
      'href',
      '/api/auth/entra/login',
    );
  });

  it('rejects a PIN that is not 6 digits without calling the API', async () => {
    reply(200, {});
    wrap(<LoginForm />);
    await userEvent.type(screen.getByLabelText('Mã nhân viên'), 'PNJP000001');
    await userEvent.type(screen.getByLabelText('Mã PIN (6 chữ số)'), '123');
    await userEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));
    expect(await screen.findByText('Mã PIN gồm đúng 6 chữ số')).toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
  });

  it('sends a temporary-PIN user to /change-pin', async () => {
    reply(200, { user: { pinMustChange: true } });
    wrap(<LoginForm />);
    await userEvent.type(screen.getByLabelText('Mã nhân viên'), 'PNJP000001');
    await userEvent.type(screen.getByLabelText('Mã PIN (6 chữ số)'), '123456');
    await userEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/change-pin'));
  });

  it('sends a normal user to /today', async () => {
    reply(200, { user: { pinMustChange: false } });
    wrap(<LoginForm />);
    await userEvent.type(screen.getByLabelText('Mã nhân viên'), 'PNJP000001');
    await userEvent.type(screen.getByLabelText('Mã PIN (6 chữ số)'), '123456');
    await userEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/today'));
  });

  it("drops the previous person's cached data on sign-in", async () => {
    reply(200, { user: { pinMustChange: false } });
    const client = new QueryClient();
    client.setQueryData(['/registrations/me/week', null], { state: 'PUBLISHED' });
    render(
      <QueryClientProvider client={client}>
        <LoginForm />
      </QueryClientProvider>,
    );
    await userEvent.type(screen.getByLabelText('Mã nhân viên'), 'PNJP000001');
    await userEvent.type(screen.getByLabelText('Mã PIN (6 chữ số)'), '123456');
    await userEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/today'));
    expect(client.getQueryData(['/registrations/me/week', null])).toBeUndefined();
  });

  it('shows the invalid-credentials message and clears the PIN', async () => {
    reply(401, { statusCode: 401, code: 'INVALID_CREDENTIALS', message: 'x' });
    wrap(<LoginForm />);
    await userEvent.type(screen.getByLabelText('Mã nhân viên'), 'PNJP000001');
    await userEvent.type(screen.getByLabelText('Mã PIN (6 chữ số)'), '000000');
    await userEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Mã nhân viên hoặc mã PIN không đúng.',
    );
    expect(screen.getByLabelText('Mã PIN (6 chữ số)')).toHaveValue('');
  });

  it('shows when a locked account can try again, in Vietnam time', async () => {
    reply(423, {
      statusCode: 423,
      code: 'PIN_LOCKED',
      message: 'x',
      lockedUntil: '2026-10-19T01:15:00.000Z',
    });
    wrap(<LoginForm />);
    await userEvent.type(screen.getByLabelText('Mã nhân viên'), 'PNJP000001');
    await userEvent.type(screen.getByLabelText('Mã PIN (6 chữ số)'), '000000');
    await userEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Thử lại sau 08:15.');
  });
});

describe('ChangePinForm', () => {
  const fill = async (current: string, next: string, confirm: string) => {
    await userEvent.type(screen.getByLabelText('Mã PIN hiện tại'), current);
    await userEvent.type(screen.getByLabelText('Mã PIN mới'), next);
    await userEvent.type(screen.getByLabelText('Nhập lại mã PIN mới'), confirm);
    await userEvent.click(screen.getByRole('button', { name: 'Đổi mã PIN' }));
  };

  it('explains the forced change and hides the skip button', () => {
    wrap(<ChangePinForm forced />);
    expect(screen.getByText(/lần đăng nhập đầu tiên/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Để sau' })).not.toBeInTheDocument();
  });

  it('requires the confirmation to match', async () => {
    reply(204);
    wrap(<ChangePinForm forced />);
    await fill('123456', '654321', '654322');
    expect(await screen.findByText('Hai mã PIN mới không giống nhau')).toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
  });

  it('requires the new PIN to differ from the current one', async () => {
    reply(204);
    wrap(<ChangePinForm forced />);
    await fill('123456', '123456', '123456');
    expect(await screen.findByText('Mã PIN mới phải khác mã PIN hiện tại.')).toBeInTheDocument();
  });

  it('posts only the current and new PIN, then goes to /today', async () => {
    reply(204);
    wrap(<ChangePinForm forced />);
    await fill('123456', '654321', '654321');
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/today'));
    const [, init] = vi.mocked(fetch).mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(init.body as string)).toEqual({ currentPin: '123456', newPin: '654321' });
  });
});

describe('PIN fields', () => {
  it('can show the typed PIN and hide it again', async () => {
    wrap(<LoginForm />);
    const pin = screen.getByLabelText('Mã PIN (6 chữ số)');
    expect(pin).toHaveAttribute('type', 'password');
    await userEvent.click(screen.getByRole('button', { name: 'Hiện mã PIN' }));
    expect(pin).toHaveAttribute('type', 'text');
    await userEvent.click(screen.getByRole('button', { name: 'Ẩn mã PIN' }));
    expect(pin).toHaveAttribute('type', 'password');
  });

  // A submit before hydration is a native form submit: with the default GET the PIN would land in
  // the URL (history, proxy logs, Referer).
  it('posts natively, so a PIN never goes into the URL', () => {
    const { container, unmount } = wrap(<LoginForm />);
    expect(container.querySelector('form')).toHaveAttribute('method', 'post');
    unmount();
    const changePin = wrap(<ChangePinForm forced />);
    expect(changePin.container.querySelector('form')).toHaveAttribute('method', 'post');
  });
});
