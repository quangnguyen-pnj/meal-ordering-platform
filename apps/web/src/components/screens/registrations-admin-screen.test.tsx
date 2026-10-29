import type { RegistrationGridView } from '@/lib/api-types';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { useConfirm } from '@/components/confirm-dialog';
import { GridDayTabs } from './registrations-admin-screen';

const days: RegistrationGridView['days'] = ['2026-11-02', '2026-11-03'].map((date) => ({
  date,
  window: 'ON_BEHALF',
  lockAt: `${date}T01:00:00.000Z`,
  items: [],
}));

/** The grid's guard: with unsaved changes, switching day asks first. */
function Guarded({ onConfirm }: { onConfirm: () => void }) {
  const [confirm, dialog] = useConfirm();
  const [date, setDate] = useState(days[0]!.date);
  return (
    <>
      {dialog}
      <GridDayTabs
        days={days}
        value={date}
        onPick={async (d) => {
          onConfirm();
          if (await confirm({ title: 'Bỏ thay đổi?' })) setDate(d);
        }}
      />
    </>
  );
}

describe('bulk entry grid day tabs', () => {
  it('"Hủy" on the unsaved-changes confirm closes it for good and keeps the day', async () => {
    const user = userEvent.setup();
    let asked = 0;
    render(<Guarded onConfirm={() => asked++} />);

    await user.click(screen.getByRole('tab', { name: /Thứ Ba/ }));
    expect(await screen.findByRole('alertdialog')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Hủy' }));

    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());
    // Focus is back on the clicked tab; that alone must not switch the day or ask again.
    expect(screen.getByRole('tab', { name: /Thứ Ba/ })).toHaveFocus();
    expect(screen.getByRole('tab', { name: /Thứ Hai/ })).toHaveAttribute('aria-selected', 'true');
    expect(asked).toBe(1);
  });

  it('switches the day on Enter, not when arrow keys move the focus', async () => {
    const user = userEvent.setup();
    let asked = 0;
    render(<Guarded onConfirm={() => asked++} />);

    screen.getByRole('tab', { name: /Thứ Hai/ }).focus();
    await user.keyboard('{ArrowRight}');
    expect(screen.getByRole('tab', { name: /Thứ Ba/ })).toHaveFocus();
    expect(asked).toBe(0);

    // keyDown only: user-event would also send Enter's click to the dialog's focused "Hủy".
    fireEvent.keyDown(screen.getByRole('tab', { name: /Thứ Ba/ }), { key: 'Enter' });
    expect(asked).toBe(1);
    await user.click(await screen.findByRole('button', { name: 'Xác nhận' }));
    await waitFor(() =>
      expect(screen.getByRole('tab', { name: /Thứ Ba/ })).toHaveAttribute('aria-selected', 'true'),
    );
  });
});
