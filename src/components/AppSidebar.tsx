"use client";

import { useCallback, useState } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import {
  LayoutDashboard,
  Package,
  Warehouse,
  Truck,
  Tag,
  Store,
  ArrowRightLeft,
  ClipboardList,
  TrendingUp,
  CreditCard,
  Settings,
  LogOut,
  Menu,
  ChevronDown,
  ChevronRight,
} from "lucide-react";
import { useI18n } from "@/i18n/context";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { ScrollArea } from "@/components/ui/scroll-area";
import {
  Sheet,
  SheetContent,
  SheetTrigger,
  SheetTitle,
} from "@/components/ui/sheet";
import LanguageSwitcher from "@/components/LanguageSwitcher";
import { createBrowserSupabaseClient } from "@/lib/supabase-browser";
import { cn } from "@/lib/utils";

interface NavItem {
  href: string;
  labelKey: keyof ReturnType<typeof useI18n>["t"];
  icon: React.ReactNode;
}

interface NavGroup {
  labelKey: keyof ReturnType<typeof useI18n>["t"];
  items: NavItem[];
  defaultOpen?: boolean;
}

const iconClass = "h-4 w-4";

const navGroups: NavGroup[] = [
  {
    labelKey: "masterData",
    defaultOpen: true,
    items: [
      { href: "/products", labelKey: "products", icon: <Package className={iconClass} /> },
      { href: "/warehouses", labelKey: "warehouses", icon: <Warehouse className={iconClass} /> },
      { href: "/suppliers", labelKey: "suppliers", icon: <Truck className={iconClass} /> },
      { href: "/categories", labelKey: "categories", icon: <Tag className={iconClass} /> },
      { href: "/stores", labelKey: "stores", icon: <Store className={iconClass} /> },
    ],
  },
  {
    labelKey: "reportsGroup",
    defaultOpen: false,
    items: [
      { href: "/reports/inventory", labelKey: "reportInventory", icon: <ClipboardList className={iconClass} /> },
      { href: "/reports/movement", labelKey: "reportMovement", icon: <TrendingUp className={iconClass} /> },
      { href: "/reports/supplier-debt", labelKey: "reportSupplierDebt", icon: <CreditCard className={iconClass} /> },
    ],
  },
];

const topItems: NavItem[] = [
  { href: "/", labelKey: "dashboard", icon: <LayoutDashboard className={iconClass} /> },
];

const operationsItem: NavItem = {
  href: "/operations",
  labelKey: "operations",
  icon: <ArrowRightLeft className={iconClass} />,
};

const bottomItems: NavItem[] = [
  { href: "/settings", labelKey: "settings", icon: <Settings className={iconClass} /> },
];

function SidebarNavLink({
  item,
  pathname,
  onClick,
}: {
  item: NavItem;
  pathname: string;
  onClick?: () => void;
}) {
  const { t } = useI18n();
  const isActive =
    item.href === "/"
      ? pathname === "/"
      : pathname === item.href || pathname.startsWith(item.href + "/");

  return (
    <Link
      href={item.href}
      onClick={onClick}
      className={cn(
        "flex items-center gap-3 rounded-md px-3 py-2 text-sm transition-colors",
        isActive
          ? "bg-accent text-accent-foreground font-medium"
          : "text-muted-foreground hover:bg-accent/50 hover:text-accent-foreground"
      )}
    >
      {item.icon}
      {String(t[item.labelKey])}
    </Link>
  );
}

function SidebarContent({ onNavigate }: { onNavigate?: () => void }) {
  const { t } = useI18n();
  const pathname = usePathname();
  const router = useRouter();
  const [signingOut, setSigningOut] = useState(false);
  const [openGroups, setOpenGroups] = useState<Record<string, boolean>>(() => {
    const initial: Record<string, boolean> = {};
    navGroups.forEach((g) => {
      initial[g.labelKey] = g.defaultOpen ?? false;
    });
    return initial;
  });

  const toggleGroup = (key: string) => {
    setOpenGroups((prev) => ({ ...prev, [key]: !prev[key] }));
  };

  const handleSignOut = useCallback(async () => {
    setSigningOut(true);
    try {
      const supabase = createBrowserSupabaseClient();
      await supabase.auth.signOut();
      router.push("/login");
      router.refresh();
    } finally {
      setSigningOut(false);
    }
  }, [router]);

  return (
    <div className="flex h-full flex-col">
      {/* Logo */}
      <div className="flex h-14 items-center px-4">
        <Link href="/" className="text-lg font-bold" onClick={onNavigate}>
          {t.appName}
        </Link>
      </div>

      <Separator />

      {/* Nav */}
      <ScrollArea className="flex-1 px-3 py-3">
        <nav className="flex flex-col gap-1">
          {/* Top items */}
          {topItems.map((item) => (
            <SidebarNavLink
              key={item.href}
              item={item}
              pathname={pathname}
              onClick={onNavigate}
            />
          ))}

          {/* Groups */}
          {navGroups.map((group) => {
            const isOpen = openGroups[group.labelKey];
            return (
              <div key={group.labelKey} className="mt-2">
                <button
                  onClick={() => toggleGroup(group.labelKey)}
                  className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground hover:text-foreground transition-colors"
                >
                  {isOpen ? (
                    <ChevronDown className="h-3 w-3" />
                  ) : (
                    <ChevronRight className="h-3 w-3" />
                  )}
                  {String(t[group.labelKey])}
                </button>
                {isOpen && (
                  <div className="ml-2 flex flex-col gap-0.5">
                    {group.items.map((item) => (
                      <SidebarNavLink
                        key={item.href}
                        item={item}
                        pathname={pathname}
                        onClick={onNavigate}
                      />
                    ))}
                  </div>
                )}
              </div>
            );
          })}

          {/* Operations */}
          <div className="mt-2">
            <SidebarNavLink
              item={operationsItem}
              pathname={pathname}
              onClick={onNavigate}
            />
          </div>

          <Separator className="my-2" />

          {/* Bottom items */}
          {bottomItems.map((item) => (
            <SidebarNavLink
              key={item.href}
              item={item}
              pathname={pathname}
              onClick={onNavigate}
            />
          ))}
        </nav>
      </ScrollArea>

      {/* Footer */}
      <Separator />
      <div className="flex items-center justify-between px-4 py-3">
        <LanguageSwitcher />
        <Button
          variant="ghost"
          size="sm"
          disabled={signingOut}
          onClick={handleSignOut}
          className="gap-2"
        >
          <LogOut className="h-4 w-4" />
          {signingOut ? t.loggingOut : t.logOut}
        </Button>
      </div>
    </div>
  );
}

export default function AppSidebar({ children }: { children: React.ReactNode }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);

  return (
    <div className="flex min-h-screen">
      {/* Desktop sidebar */}
      <aside className="hidden w-60 shrink-0 border-r bg-card md:block">
        <div className="sticky top-0 h-screen">
          <SidebarContent />
        </div>
      </aside>

      {/* Mobile sidebar */}
      <Sheet open={open} onOpenChange={setOpen}>
        <div className="sticky top-0 z-40 flex h-14 items-center border-b bg-card px-4 md:hidden">
          <SheetTrigger asChild>
            <Button variant="ghost" size="sm">
              <Menu className="h-5 w-5" />
            </Button>
          </SheetTrigger>
          <span className="ml-3 text-lg font-bold">{t.appName}</span>
        </div>
        <SheetContent side="left" className="w-60 p-0">
          <SheetTitle className="sr-only">{t.navigation}</SheetTitle>
          <SidebarContent onNavigate={() => setOpen(false)} />
        </SheetContent>
      </Sheet>

      {/* Main content */}
      <main className="flex-1 overflow-x-hidden">{children}</main>
    </div>
  );
}
