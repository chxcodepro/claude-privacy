import { Check, ChevronsUpDown } from "lucide-react";
import { useMemo } from "react";

import { Button } from "@claude-privacy/ui/components/button";
import { Combobox } from "@claude-privacy/ui/components/combobox";
import { Input } from "@claude-privacy/ui/components/input";

import zoneIds from "#src/iana-time-zones.json";
import { isTimeZone, utcOffset } from "#src/privacy-config.ts";

interface ZoneOption {
  id: string;
  offset: string;
}

const toOption = (id: string): ZoneOption => ({ id, offset: utcOffset(id) });
const matches = (zone: ZoneOption, query: string) => {
  const text = query.trim().toLowerCase();
  return zone.id.toLowerCase().includes(text) || zone.offset.toLowerCase().includes(text);
};

export function TimezonePicker({
  value,
  label,
  disabled,
  onChange,
}: {
  value: string;
  label: string;
  disabled: boolean;
  onChange: (value: string) => void;
}) {
  const catalogue = useMemo(() => zoneIds.filter(isTimeZone).map(toOption), []);
  // Existing configurations can use an IANA alias omitted from the canonical catalogue.
  const stored = catalogue.find((zone) => zone.id === value);
  const selected = stored ?? toOption(value);
  const options = stored ? catalogue : [selected, ...catalogue];

  return (
    <Combobox.Root<ZoneOption>
      autoHighlight
      disabled={disabled}
      filter={matches}
      isItemEqualToValue={(item, current) => item.id === current.id}
      itemToStringLabel={(item) => item.id}
      items={options}
      onValueChange={(item) => {
        if (item) onChange(item.id);
      }}
      value={selected}
    >
      <Combobox.Trigger
        aria-label={`${label}：${selected.id} · ${selected.offset}`}
        className="min-w-0 flex-1 justify-between"
        render={<Button size="lg" variant="outline" type="button" />}
      >
        <span className="min-w-0 truncate">
          {selected.id} · {selected.offset}
        </span>
        <ChevronsUpDown className="shrink-0 text-muted-foreground" />
      </Combobox.Trigger>
      <Combobox.Portal>
        <Combobox.Positioner className="z-50" sideOffset={5} collisionPadding={12}>
          <Combobox.Popup
            data-slot="timezone-options"
            className="border-border bg-popover text-popover-foreground grid w-(--anchor-width) max-h-(--available-height) min-w-44 grid-rows-[auto_minmax(0,1fr)] overflow-hidden rounded-lg border shadow-lg transition-opacity data-ending-style:opacity-0 data-starting-style:opacity-0 motion-reduce:transition-none"
          >
            <div className="p-2">
              <Combobox.Input
                aria-label="搜索时区"
                placeholder="名称或 UTC 偏移"
                render={<Input className="h-9" />}
              />
            </div>
            <Combobox.Empty className="px-3 pb-3 text-sm text-muted-foreground">
              未找到时区
            </Combobox.Empty>
            <Combobox.List className="max-h-64 overflow-y-auto overscroll-contain px-1 pb-1 empty:hidden">
              {(zone: ZoneOption) => (
                <Combobox.Item
                  key={zone.id}
                  value={zone}
                  className="flex min-h-10 cursor-default items-center gap-2 rounded-md px-2 text-sm outline-none data-highlighted:bg-muted"
                >
                  <span className="min-w-0 flex-1 break-words">{zone.id}</span>
                  <span className="shrink-0 whitespace-nowrap text-xs text-muted-foreground">
                    {zone.offset}
                  </span>
                  <span className="flex size-4 shrink-0 items-center justify-center">
                    <Combobox.ItemIndicator>
                      <Check className="size-4 text-primary" />
                    </Combobox.ItemIndicator>
                  </span>
                </Combobox.Item>
              )}
            </Combobox.List>
          </Combobox.Popup>
        </Combobox.Positioner>
      </Combobox.Portal>
    </Combobox.Root>
  );
}
