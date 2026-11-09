import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { DatePicker, parseIsoDate, toIsoDate } from './date-picker';

function Harness({ initial }: { initial: string }) {
  const [value, setValue] = useState(initial);
  return (
    <>
      <DatePicker id="d" value={value} onChange={setValue} placeholder="Chọn ngày" />
      <p data-testid="value">{value}</p>
    </>
  );
}

describe('DatePicker', () => {
  it('shows the date as dd/MM/yyyy, not the browser date input', () => {
    const { container } = render(<Harness initial="2026-11-03" />);
    expect(screen.getByRole('button', { name: /03\/11\/2026/ })).toBeInTheDocument();
    expect(container.querySelector('input[type="date"]')).toBeNull();
  });

  it('shows the placeholder when empty', () => {
    render(<Harness initial="" />);
    expect(screen.getByRole('button', { name: /Chọn ngày/ })).toBeInTheDocument();
  });

  it('opens a Vietnamese calendar on the selected month and picks a day', async () => {
    const user = userEvent.setup();
    render(<Harness initial="2026-11-03" />);
    await user.click(screen.getByRole('button', { name: /03\/11\/2026/ }));
    expect(screen.getByText('Tháng Mười Một 2026')).toBeInTheDocument();
    expect(screen.getByText('CN')).toBeInTheDocument();
    await user.click(
      screen
        .getAllByRole('gridcell')
        .find((c) => c.getAttribute('data-day') === '2026-11-12')!
        .querySelector('button')!,
    );
    expect(screen.getByTestId('value')).toHaveTextContent('2026-11-12');
    // The popover closes after a pick.
    expect(screen.queryByRole('grid')).toBeNull();
  });

  it('round-trips YYYY-MM-DD without a timezone shift', () => {
    expect(toIsoDate(parseIsoDate('2026-01-01')!)).toBe('2026-01-01');
    expect(toIsoDate(parseIsoDate('2026-12-31')!)).toBe('2026-12-31');
    expect(parseIsoDate('')).toBeUndefined();
    expect(parseIsoDate('2026-02-31')?.getMonth()).not.toBe(1);
  });
});
