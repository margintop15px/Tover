/*!
 * Built by Revolist OU ❤️
 */
import { d as defineTheme, B as BasePlugin } from './row-resize.plugin-qIO5uzue.js';
export { z as AFTER_ROW_RESIZE_EVENT, A as AutoSizeColumnPlugin, q as BEFORE_COLUMN_DRAG_END_EVENT, x as BEFORE_ROW_RESIZE_EVENT, p as COLUMN_DRAG_END_EVENT, o as COLUMN_DRAG_MOVE_EVENT, s as COLUMN_DRAG_START_EVENT, C as ColumnAutoSizeMode, u as ColumnMovePlugin, k as DEFAULT_BLANK_SEMANTICS, J as DEFAULT_MIN_ROW_HEIGHT, D as DimensionStore, b as ExportCsv, E as ExportFilePlugin, c as FILTER_CONFIG_CHANGED_EVENT, F as FILTER_TRIMMED_TYPE, e as FILTE_PANEL, f as FilterPlugin, G as GroupingRowPlugin, H as ROW_RESIZE_CANCEL_EVENT, y as ROW_RESIZE_EVENT, R as ROW_RESIZE_HANDLE_CLASS, I as RowResizePlugin, S as SelectionStore, P as SortingPlugin, a as StretchColumn, L as clampRowResizeHeight, N as createRowResizePatch, V as defaultCellCompare, W as descCellCompare, m as doCollapse, n as doExpand, g as filterCoreFunctionsIndexedByType, j as filterNames, h as filterTypes, w as getColumnDragPosition, Y as getComparer, v as getLeftRelative, X as getNextOrder, M as getRowResizeIndexes, T as getSortingIndex, Q as hasActiveSorting, l as isBlankValue, i as isStretchPlugin, O as mergeRowResizeDefinitions, r as resolveBlankSemantics, K as resolveRowResizeConfig, U as sortIndexByItems, t as themeTokenCssVariables } from './row-resize.plugin-qIO5uzue.js';
export { B as BEFORE_GROUPING_APPLY_EVENT, o as GROUPING_ROW_TYPE, j as GROUP_COLUMN_PROP, G as GROUP_DEPTH, h as GROUP_EXPANDED, l as GROUP_EXPAND_BTN, m as GROUP_EXPAND_EVENT, k as GROUP_ORIGINAL_INDEX, f as PSEUDO_GROUP_COLUMN, P as PSEUDO_GROUP_ITEM, d as PSEUDO_GROUP_ITEM_ID, e as PSEUDO_GROUP_ITEM_VALUE, c as columnTypes, a as cropCellToMax, I as gatherGroup, s as gatherGrouping, z as getCellData, C as getCellDataParsed, A as getCellRaw, J as getColumnByProp, E as getColumnSizes, D as getColumnType, H as getColumns, q as getExpanded, t as getGroupingName, x as getParsedGroup, g as getRange, p as getSource, F as isColGrouping, u as isGrouping, v as isGroupingColumn, b as isRangeSingleCell, i as isRowType, y as isSameGroup, w as measureEqualDepth, n as nextCell, r as rowTypes } from './column.service-pu_fdQ0S.js';
export { d as dispatch, a as dispatchByEvent } from './header-cell-renderer-YCiVIQ2E.js';
export { C as CellRenderer, G as GroupingRowRenderer, S as SortingSign, e as expandEvent, a as expandSvgIconVNode, r as renderGroupCells } from './cell-renderer-D9ze8OJw.js';
export { a as applyMixins, f as findPositionInArray, g as getScrollbarSize, m as mergeSortedArray, p as pushSorted, r as range, s as scaleValue, t as timeout } from './index-BvMNbQyq.js';
export { T as TextEditor } from './text-editor-BVb0idxe.js';
export { k as isAll, c as isClear, h as isCopy, a as isCtrlKey, b as isCtrlMetaKey, g as isCut, m as isEditInput, n as isEditorCtrConstructible, f as isEnterKeyValue, i as isMetaKey, j as isPaste, l as isShortcutModifier, d as isTab, e as isTabKeyValue } from './edit.utils-3ZbhQCv_.js';
export { h } from './index-CtimkLsB.js';
export { C as CELL_CLASS, A as CELL_HANDLER_CLASS, n as DATA_COL, o as DATA_ROW, q as DISABLED_CLASS, y as DRAGGABLE_CLASS, B as DRAGG_TEXT, x as DRAG_ICON_CLASS, D as DataStore, E as EDIT_INPUT_WR, F as FOCUS_CLASS, G as GRID_INTERNALS, w as HEADER_ACTUAL_ROW_CLASS, H as HEADER_CLASS, v as HEADER_ROW_CLASS, u as HEADER_SORTABLE_CLASS, M as MIN_COL_SIZE, z as MOBILE_CLASS, R as RESIZE_INTERVAL, I as ROW_FOCUSED_CLASS, r as ROW_HEADER_TYPE, S as SELECTION_BORDER_CLASS, T as TMP_SELECTION_BG_CLASS, j as calculateDimensionData, J as codesLetter, h as gatherTrimmedItems, l as getItemByIndex, k as getItemByPosition, g as getPhysical, b as getSourceItem, f as getSourceItemVirtualIndexByProp, c as getSourcePhysicalIndex, i as getVisibleItems, a as getVisibleSourceItem, K as keyValues, p as proxyPlugin, e as setItems, d as setSourceByPhysicalIndex, s as setSourceByVirtualIndex, m as setStore, t as trimmedPlugin } from './dimension.helpers-Cln9sALu.js';
export { V as ViewportStore, b as addMissingItems, j as calculateRowHeaderSize, c as clampViewportCoordinate, f as getFirstItem, d as getItems, h as getLastItem, a as getUpdatedItemsByPosition, g as getViewportMaxCoordinate, i as isActiveRange, e as isActiveRangeOutsideLastItem, r as recombineByOffset, s as setItemSizes, u as updateMissingAndRange } from './viewport.store-DWMKRDSH.js';
export { A as AND_OR_BUTTON, e as AndOrButton, a as FILTER_BUTTON_ACTIVE, F as FILTER_BUTTON_CLASS, b as FILTER_PROP, c as FilterButton, R as REORDER_BUTTON, f as ReorderButton, T as TRASH_BUTTON, d as TrashButton, i as isFilterBtn } from './filter.button-CEPUxLUo.js';
import './debounce-PCRWZliA.js';

const REVOGRID_EVENTS = new Map([
    ['contentsizechanged', 'contentsizechanged'],
    ['beforeedit', 'beforeedit'],
    ['beforerangeedit', 'beforerangeedit'],
    ['afteredit', 'afteredit'],
    ['beforeautofill', 'beforeautofill'],
    ['beforerange', 'beforerange'],
    ['afterfocus', 'afterfocus'],
    ['roworderchanged', 'roworderchanged'],
    ['beforesorting', 'beforesorting'],
    ['beforesourcesortingapply', 'beforesourcesortingapply'],
    ['beforesortingapply', 'beforesortingapply'],
    ['aftersortingapply', 'aftersortingapply'],
    ['rowdragstart', 'rowdragstart'],
    ['headerclick', 'headerclick'],
    ['beforecellfocus', 'beforecellfocus'],
    ['beforefocuslost', 'beforefocuslost'],
    ['beforesourceset', 'beforesourceset'],
    ['beforeanysource', 'beforeanysource'],
    ['aftersourceset', 'aftersourceset'],
    ['afteranysource', 'afteranysource'],
    ['beforecolumnsgather', 'beforecolumnsgather'],
    ['beforecolumnsset', 'beforecolumnsset'],
    ['beforecolumnapplied', 'beforecolumnapplied'],
    ['aftercolumnsset', 'aftercolumnsset'],
    ['beforefilterapply', 'beforefilterapply'],
    ['beforefiltertrimmed', 'beforefiltertrimmed'],
    ['beforetrimmed', 'beforetrimmed'],
    ['aftertrimmed', 'aftertrimmed'],
    ['viewportscroll', 'viewportscroll'],
    ['beforeexport', 'beforeexport'],
    ['beforeeditstart', 'beforeeditstart'],
    ['aftercolumnresize', 'aftercolumnresize'],
    ['beforerowdefinition', 'beforerowdefinition'],
    ['filterconfigchanged', 'filterconfigchanged'],
    ['sortingconfigchanged', 'sortingconfigchanged'],
    ['rowheaderschanged', 'rowheaderschanged'],
    ['beforegridrender', 'beforegridrender'],
    ['aftergridrender', 'aftergridrender'],
    ['aftergridinit', 'aftergridinit'],
    ['additionaldatachanged', 'additionaldatachanged'],
    ['afterthemechanged', 'afterthemechanged'],
    ['created', 'created'],
    ['beforepaste', 'beforepaste'],
    ['beforepasteapply', 'beforepasteapply'],
    ['pasteregion', 'pasteregion'],
    ['afterpasteapply', 'afterpasteapply'],
    ['beforecut', 'beforecut'],
    ['clearregion', 'clearregion'],
    ['beforecopy', 'beforecopy'],
    ['beforecopyapply', 'beforecopyapply'],
    ['copyregion', 'copyregion'],
    ['beforerowrender', 'beforerowrender'],
    ['afterrender', 'afterrender'],
    ['beforecellrender', 'beforecellrender'],
    ['beforedatarender', 'beforedatarender'],
    ['dragstartcell', 'dragstartcell'],
    ['celleditinit', 'celleditinit'],
    ['closeedit', 'closeedit'],
    ['filterChange', 'filterChange'],
    ['resetChange', 'resetChange'],
    ['beforefocusrender', 'beforefocusrender'],
    ['beforescrollintoview', 'beforescrollintoview'],
    ['beforeheaderclick', 'beforeheaderclick'],
    ['headerresize', 'headerresize'],
    ['beforeheaderresize', 'beforeheaderresize'],
    ['headerdblclick', 'headerdblclick'],
    ['beforeheaderrender', 'beforeheaderrender'],
    ['beforegroupheaderrender', 'beforegroupheaderrender'],
    ['afterheaderrender', 'afterheaderrender'],
    ['columndragstart', 'columndragstart'],
    ['columndragmousemove', 'columndragmousemove'],
    ['beforecolumndragend', 'beforecolumndragend'],
    ['columndragend', 'columndragend'],
    ['beforerowresize', 'beforerowresize'],
    ['rowresize', 'rowresize'],
    ['afterrowresize', 'afterrowresize'],
    ['rowresizecancel', 'rowresizecancel'],
    ['rowdragstartinit', 'rowdragstartinit'],
    ['rowdragendinit', 'rowdragendinit'],
    ['rowdragmoveinit', 'rowdragmoveinit'],
    ['rowdragmousemove', 'rowdragmousemove'],
    ['rowdropinit', 'rowdropinit'],
    ['roworderchange', 'roworderchange'],
    ['beforecopyregion', 'beforecopyregion'],
    ['beforepasteregion', 'beforepasteregion'],
    ['celleditapply', 'celleditapply'],
    ['beforecellfocusinit', 'beforecellfocusinit'],
    ['beforenextvpfocus', 'beforenextvpfocus'],
    ['setedit', 'setedit'],
    ['beforeapplyrange', 'beforeapplyrange'],
    ['beforesetrange', 'beforesetrange'],
    ['setrange', 'setrange'],
    ['beforeeditrender', 'beforeeditrender'],
    ['selectall', 'selectall'],
    ['canceledit', 'canceledit'],
    ['settemprange', 'settemprange'],
    ['beforesettemprange', 'beforesettemprange'],
    ['applyfocus', 'applyfocus'],
    ['focuscell', 'focuscell'],
    ['beforerangedataapply', 'beforerangedataapply'],
    ['selectionchangeinit', 'selectionchangeinit'],
    ['beforerangecopyapply', 'beforerangecopyapply'],
    ['rangeeditapply', 'rangeeditapply'],
    ['clipboardrangecopy', 'clipboardrangecopy'],
    ['clipboardrangepaste', 'clipboardrangepaste'],
    ['beforekeydown', 'beforekeydown'],
    ['beforekeyup', 'beforekeyup'],
    ['beforecellsave', 'beforecellsave'],
    ['celledit', 'celledit'],
    ['scrollview', 'scrollview'],
    ['ref', 'ref'],
    ['scrollvirtual', 'scrollvirtual'],
    ['scrollviewport', 'scrollviewport'],
    ['resizeviewport', 'resizeviewport'],
    ['scrollchange', 'scrollchange'],
    ['scrollviewportsilent', 'scrollviewportsilent'],
    ['html', 'html']
]);

const modernSystemFont = "Inter, ui-sans-serif, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif";
/** Maps compact, typed preset data to the public semantic theme-token contract. */
function createPresetTokens({ foundation, grid, filterPanel, typography, selection, buttons, }) {
    const [primary, primaryTransparent, background, foreground, divider, shadow, text, border,] = foundation;
    const [headerBg, headerColor, headerBorder, headerFocusedBg, headerHoverBg, cellBorder, cellVerticalBorder, focusedBg, rowHover, rowHeadersBg, rowHeadersColor, cellDisabledBg,] = grid;
    const [filterPanelBg, filterPanelBorder, filterPanelShadow, filterPanelInputBg, filterPanelDivider, filterPanelSelectBorder, filterPanelSelectBorderHover, filterPanelReorderAccent, filterPanelReorderColor, filterPanelText, filterPanelMutedText, filterPanelFocusRing, filterPanelIcon, filterPanelIconActive,] = filterPanel;
    const [fontSize, headerHeight, headerFontSize, headerFontWeight, headerTextTransform, headerTextAlign, cellTextAlign, headerPadding, cellPadding,] = typography;
    const [selectionBorder, selectionBg, autofillHandleBg, autofillHandleBorder, rangeHandleBg, temporaryRangeBorder, temporarySelectionBorder, headerResizeHover,] = selection;
    const [buttonText, buttonBg, buttonSuccessBg, buttonDangerBg, buttonOutlineBorder, buttonOutlineText,] = buttons;
    return Object.assign(Object.assign(Object.assign(Object.assign(Object.assign({ primary,
        primaryTransparent,
        background,
        foreground,
        divider,
        shadow,
        text,
        border,
        headerBg,
        headerColor,
        headerBorder }, (headerFocusedBg ? { headerFocusedBg } : {})), (headerHoverBg ? { headerHoverBg } : {})), { cellBorder }), (cellVerticalBorder ? { cellVerticalBorder } : {})), { focusedBg,
        rowHover,
        rowHeadersBg,
        rowHeadersColor,
        cellDisabledBg,
        filterPanelBg,
        filterPanelBorder,
        filterPanelShadow,
        filterPanelInputBg,
        filterPanelDivider,
        filterPanelSelectBorder,
        filterPanelSelectBorderHover,
        filterPanelReorderAccent,
        filterPanelReorderColor,
        filterPanelText,
        filterPanelMutedText,
        filterPanelFocusRing,
        filterPanelIcon,
        filterPanelIconActive, fontFamily: modernSystemFont, fontSize,
        headerHeight,
        headerFontSize,
        headerFontWeight,
        headerTextTransform,
        headerTextAlign,
        cellTextAlign,
        headerPadding,
        cellPadding,
        selectionBorder,
        selectionBg,
        autofillHandleBg,
        autofillHandleBorder,
        rangeHandleBg,
        temporaryRangeBorder,
        temporarySelectionBorder,
        headerResizeHover,
        buttonText,
        buttonBg,
        buttonSuccessBg,
        buttonDangerBg,
        buttonOutlineBorder,
        buttonOutlineText });
}

const oceanFoundation = [
    '#2563eb',
    'rgba(37, 99, 235, 0.88)',
    '#f8fafc',
    '#0f172a',
    '#cbd5e1',
    'rgba(15, 23, 42, 0.16)',
    '#334155',
    '#d8e2ee',
];
const oceanGrid = [
    '#eef4fb',
    '#0f172a',
    '#d7e2ee',
    null,
    null,
    '#e5edf5',
    null,
    '#dbeafe',
    '#eff6ff',
    '#e7f0fa',
    '#475569',
    '#f1f5f9',
];
const oceanFilterPanel = [
    '#ffffff',
    '#dbe4ef',
    'rgba(15, 23, 42, 0.18)',
    '#f1f5f9',
    '#e2e8f0',
    '#cbd5e1',
    '#2563eb',
    '#2563eb',
    '#64748b',
    '#334155',
    '#64748b',
    '0 0 0 3px rgba(37, 99, 235, 0.2)',
    '#64748b',
    '#2563eb',
];
const oceanTypography = [
    '13px',
    '44px',
    '12px',
    '650',
    'none',
    'left',
    'left',
    '0 14px',
    '0 14px',
];
const oceanSelection = [
    '#2563eb',
    'rgba(37, 99, 235, 0.1)',
    '#2563eb',
    '#ffffff',
    'rgba(37, 99, 235, 0.22)',
    '#f97316',
    '#64748b',
    '#3b82f6',
];
const oceanButtons = [
    '#ffffff',
    '#2563eb',
    '#059669',
    '#dc2626',
    '#cbd5e1',
    '#334155',
];
/** Bright blue and slate preset for data-heavy daytime interfaces. */
const oceanTheme = defineTheme({
    name: 'ocean',
    colorScheme: 'light',
    defaultRowSize: 38,
    tokens: createPresetTokens({
        foundation: oceanFoundation,
        grid: oceanGrid,
        filterPanel: oceanFilterPanel,
        typography: oceanTypography,
        selection: oceanSelection,
        buttons: oceanButtons,
    }),
});

const midnightFoundation = [
    '#8b5cf6',
    'rgba(139, 92, 246, 0.9)',
    '#0b1020',
    '#f8fafc',
    '#334155',
    'rgba(0, 0, 0, 0.5)',
    '#dbeafe',
    '#25324d',
];
const midnightGrid = [
    '#121a2f',
    '#e2e8f0',
    '#2a3855',
    null,
    null,
    '#202c46',
    null,
    '#172554',
    '#111c35',
    '#10182b',
    '#94a3b8',
    'rgba(100, 116, 139, 0.12)',
];
const midnightFilterPanel = [
    '#111827',
    '#334155',
    'rgba(0, 0, 0, 0.55)',
    '#1e293b',
    '#334155',
    '#475569',
    '#22d3ee',
    '#22d3ee',
    '#94a3b8',
    '#e2e8f0',
    '#94a3b8',
    '0 0 0 3px rgba(34, 211, 238, 0.2)',
    '#94a3b8',
    '#22d3ee',
];
const midnightTypography = [
    '13px',
    '46px',
    '12px',
    '650',
    'none',
    'left',
    'left',
    '0 14px',
    '0 14px',
];
const midnightSelection = [
    '#22d3ee',
    'rgba(34, 211, 238, 0.12)',
    '#22d3ee',
    '#0b1020',
    'rgba(34, 211, 238, 0.24)',
    '#f472b6',
    '#a78bfa',
    '#22d3ee',
];
const midnightButtons = [
    '#ffffff',
    '#7c3aed',
    '#059669',
    '#e11d48',
    '#475569',
    '#e2e8f0',
];
/** Deep navy preset with cyan selection and violet action accents. */
const midnightTheme = defineTheme({
    name: 'midnight',
    colorScheme: 'dark',
    defaultRowSize: 40,
    tokens: createPresetTokens({
        foundation: midnightFoundation,
        grid: midnightGrid,
        filterPanel: midnightFilterPanel,
        typography: midnightTypography,
        selection: midnightSelection,
        buttons: midnightButtons,
    }),
});

const auroraFoundation = [
    '#34d399',
    'rgba(52, 211, 153, 0.88)',
    '#071714',
    '#ecfdf5',
    '#285047',
    'rgba(0, 0, 0, 0.5)',
    '#d1fae5',
    '#1c3d36',
];
const auroraGrid = [
    '#0c2420',
    '#ecfdf5',
    '#245047',
    null,
    null,
    '#17352f',
    null,
    '#103d34',
    '#0d2e28',
    '#0a201c',
    '#86cbb8',
    'rgba(110, 231, 183, 0.08)',
];
const auroraFilterPanel = [
    '#0b201c',
    '#285047',
    'rgba(0, 0, 0, 0.52)',
    '#12352e',
    '#23483f',
    '#37695d',
    '#34d399',
    '#2dd4bf',
    '#86cbb8',
    '#d1fae5',
    '#86a89f',
    '0 0 0 3px rgba(52, 211, 153, 0.2)',
    '#86a89f',
    '#5eead4',
];
const auroraTypography = [
    '12px',
    '42px',
    '11px',
    '650',
    'uppercase',
    'left',
    'left',
    '0 12px',
    '0 12px',
];
const auroraSelection = [
    '#34d399',
    'rgba(52, 211, 153, 0.12)',
    '#34d399',
    '#071714',
    'rgba(45, 212, 191, 0.22)',
    '#fbbf24',
    '#5eead4',
    '#2dd4bf',
];
const auroraButtons = [
    '#052e27',
    '#34d399',
    '#10b981',
    '#fb7185',
    '#37695d',
    '#d1fae5',
];
/** Compact graphite and evergreen preset with luminous emerald states. */
const auroraTheme = defineTheme({
    name: 'aurora',
    colorScheme: 'dark',
    defaultRowSize: 34,
    tokens: createPresetTokens({
        foundation: auroraFoundation,
        grid: auroraGrid,
        filterPanel: auroraFilterPanel,
        typography: auroraTypography,
        selection: auroraSelection,
        buttons: auroraButtons,
    }),
});

const highContrastFoundation = [
    '#003eaa',
    'rgba(0, 62, 170, 0.92)',
    '#ffffff',
    '#000000',
    '#374151',
    'rgba(0, 0, 0, 0.35)',
    '#111827',
    '#374151',
];
const highContrastGrid = [
    '#111827',
    '#ffffff',
    '#ffffff',
    '#003eaa',
    '#1f2937',
    '#6b7280',
    '#6b7280',
    '#d6e9ff',
    '#e8f2ff',
    '#e5e7eb',
    '#000000',
    '#d1d5db',
];
const highContrastFilterPanel = [
    '#ffffff',
    '#111827',
    'rgba(0, 0, 0, 0.4)',
    '#ffffff',
    '#4b5563',
    '#111827',
    '#003eaa',
    '#003eaa',
    '#374151',
    '#000000',
    '#374151',
    '0 0 0 3px #ffbf00',
    '#111827',
    '#003eaa',
];
const highContrastTypography = [
    '14px',
    '48px',
    '13px',
    '700',
    'none',
    'left',
    'left',
    '0 14px',
    '0 14px',
];
const highContrastSelection = [
    '#003eaa',
    'rgba(0, 95, 204, 0.2)',
    '#003eaa',
    '#ffffff',
    'rgba(0, 95, 204, 0.3)',
    '#a61b1b',
    '#5b21b6',
    '#ffbf00',
];
const highContrastButtons = [
    '#ffffff',
    '#003eaa',
    '#006b3c',
    '#b91c1c',
    '#111827',
    '#111827',
];
/** High-contrast light preset with dark structure and vivid blue focus states. */
const highContrastTheme = defineTheme({
    name: 'highContrast',
    colorScheme: 'light',
    defaultRowSize: 40,
    tokens: createPresetTokens({
        foundation: highContrastFoundation,
        grid: highContrastGrid,
        filterPanel: highContrastFilterPanel,
        typography: highContrastTypography,
        selection: highContrastSelection,
        buttons: highContrastButtons,
    }),
});

const highContrastDarkFoundation = [
    '#ffd400',
    'rgba(255, 212, 0, 0.94)',
    '#050505',
    '#ffffff',
    '#d1d5db',
    'rgba(0, 0, 0, 0.8)',
    '#ffffff',
    '#bfc7d5',
];
const highContrastDarkGrid = [
    '#171717',
    '#ffffff',
    '#e5e7eb',
    '#005f73',
    '#303030',
    '#737b87',
    '#737b87',
    '#182c3f',
    '#202a33',
    '#222222',
    '#ffffff',
    '#303030',
];
const highContrastDarkFilterPanel = [
    '#0a0a0a',
    '#ffffff',
    'rgba(0, 0, 0, 0.85)',
    '#171717',
    '#d1d5db',
    '#e5e7eb',
    '#ffd400',
    '#00e5ff',
    '#e5e7eb',
    '#ffffff',
    '#d1d5db',
    '0 0 0 3px #ffd400',
    '#e5e7eb',
    '#ffd400',
];
const highContrastDarkTypography = [
    '14px',
    '48px',
    '13px',
    '700',
    'none',
    'left',
    'left',
    '0 14px',
    '0 14px',
];
const highContrastDarkSelection = [
    '#00e5ff',
    'rgba(0, 229, 255, 0.2)',
    '#ffd400',
    '#000000',
    'rgba(0, 229, 255, 0.32)',
    '#ffd400',
    '#ff7ad9',
    '#ffd400',
];
const highContrastDarkButtons = [
    '#000000',
    '#ffd400',
    '#7cfc00',
    '#ff8080',
    '#ffffff',
    '#ffffff',
];
/** High-contrast dark preset with luminous yellow and cyan interaction states. */
const highContrastDarkTheme = defineTheme({
    name: 'highContrastDark',
    colorScheme: 'dark',
    defaultRowSize: 40,
    tokens: createPresetTokens({
        foundation: highContrastDarkFoundation,
        grid: highContrastDarkGrid,
        filterPanel: highContrastDarkFilterPanel,
        typography: highContrastDarkTypography,
        selection: highContrastDarkSelection,
        buttons: highContrastDarkButtons,
    }),
});

/** Accessible light and dark high-contrast choices. */
const highContrastThemeDefinitions = [
    highContrastTheme,
    highContrastDarkTheme,
];
/** Ready-to-register modern presets. Presets remain opt-in and per grid. */
const modernThemeDefinitions = [
    oceanTheme,
    midnightTheme,
    auroraTheme,
    ...highContrastThemeDefinitions,
];

/**
 * Automatically adds new rows when pasted data is larger than current rows
 * @event newRows - is triggered when new rows are added. Data of new rows can be filled with default values. If the event is prevented, no rows will be added
 */
class AutoAddRowsPlugin extends BasePlugin {
    constructor(revogrid, providers) {
        super(revogrid, providers);
        this.addEventListener('beforepasteapply', evt => this.handleBeforePasteApply(evt));
    }
    handleBeforePasteApply(event) {
        const start = this.providers.selection.focused;
        const isEditing = this.providers.selection.edit != null;
        if (!start || isEditing) {
            return;
        }
        const rowLength = this.providers.data.stores.rgRow.store.get('items').length;
        const endRow = start.y + event.detail.parsed.length;
        if (rowLength < endRow) {
            const count = endRow - rowLength;
            const newRows = Array.from({ length: count }, (_, i) => ({
                index: rowLength + i,
                data: {},
            }));
            const event = this.emit('newRows', { newRows: newRows });
            if (event.defaultPrevented) {
                return;
            }
            const items = [
                ...this.providers.data.stores.rgRow.store.get('source'),
                ...event.detail.newRows.map(j => j.data),
            ];
            this.providers.data.setData(items);
        }
    }
}

export { AutoAddRowsPlugin, BasePlugin, REVOGRID_EVENTS, auroraTheme, defineTheme, highContrastDarkTheme, highContrastTheme, highContrastThemeDefinitions, midnightTheme, modernThemeDefinitions, oceanTheme };
