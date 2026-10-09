import { useEffect, useId, useRef, useState, type InputHTMLAttributes, type ReactNode } from 'react';
import { MdAdd, MdDeleteOutline } from 'react-icons/md';
import { Button, IconButton } from '@/components/md/Button';
import { useApp } from '@/store/useApp';

export type Json = Record<string, any>;
export const object = (value: unknown): Json => value && typeof value === 'object' && !Array.isArray(value) ? value as Json : {};
export const string = (value: unknown) => typeof value === 'string' ? value : '';
export const inputClass = 'mt-2 w-full min-w-0 rounded-xl border border-outline bg-surface-container-low px-3 py-3 type-body-medium outline-none focus:border-primary';
export function Field({ label, value, onChange, note, ...rest }: { label: string; value: string; onChange: (value: string) => void; note?: string } & Omit<InputHTMLAttributes<HTMLInputElement>, 'value' | 'onChange'>) {
  const id = useId();
  return <div className="min-w-0"><label htmlFor={id} className="type-label-large">{label}</label><input id={id} className={inputClass} autoComplete="off" {...rest} value={value} onChange={e => onChange(e.target.value)} />{note && <p className="mt-1 type-body-small text-on-surface-variant">{note}</p>}</div>;
}
export function Select({ label, value, onChange, children, disabled }: { label: string; value: string; onChange: (value: string) => void; children: ReactNode; disabled?: boolean }) {
  const id = useId();
  return <div><label htmlFor={id} className="type-label-large">{label}</label><select id={id} disabled={disabled} className={inputClass} value={value} onChange={e => onChange(e.target.value)}>{children}</select></div>;
}
function PairValue({ label, value, typed, onChange }: { label: string; value: unknown; typed: boolean; onChange: (value: unknown) => void }) {
  const display = (v: unknown) => typeof v === 'string' ? v : JSON.stringify(v);
  const [text, setText] = useState(display(value));
  const focused = useRef(false);
  useEffect(() => { if (!focused.current) setText(display(value)); }, [value]);
  return <Field label={label} value={text} onFocus={() => { focused.current = true; }} onBlur={() => { focused.current = false; }} onChange={next => {
    setText(next);
    let parsed: unknown = next;
    if (typed) { try { parsed = JSON.parse(next); } catch { /* Plain strings remain strings. */ } }
    onChange(parsed);
  }} />;
}
export function Pairs({ title, value, onChange, typed = false, note, reserved = [] }: { title: string; value: Json; onChange: (value: Json) => void; typed?: boolean; note?: string; reserved?: readonly string[] }) {
  const entries = Object.entries(value);
  const change = (index: number, name: string, entry: unknown) => {
    if (reserved.includes(name) || entries.some(([key], i) => i !== index && key === name)) { useApp.getState().showSnack('同名字段已由表单配置，请修改对应字段'); return; }
    onChange(Object.fromEntries(entries.map((row, i) => i === index ? [name, entry] : row)));
  };
  return <section aria-label={title} className="space-y-3"><div className="flex flex-wrap items-center justify-between gap-2"><h3 className="type-title-medium">{title}</h3><Button variant="text" icon={<MdAdd />} disabled={Object.prototype.hasOwnProperty.call(value, '')} onClick={() => onChange({ ...value, '': '' })}>添加{title === '请求头' ? '请求头' : '选项'}</Button></div>
    {entries.map(([name, entry], index) => <div key={index} className="rounded-xl bg-surface-container p-3"><div className="flex items-start gap-2"><div className="min-w-0 flex-1 space-y-3">
      <Field label={`${title}名称 ${index + 1}`} value={name} onChange={next => change(index, next, entry)} />
      <PairValue label={`${title}值 ${index + 1}`} value={entry} typed={typed} onChange={next => change(index, name, next)} />
    </div><IconButton aria-label={`删除${title} ${index + 1}`} onClick={() => onChange(Object.fromEntries(entries.filter((_, i) => i !== index)))}><MdDeleteOutline /></IconButton></div></div>)}
    {(note || typed) && <p className="type-body-small text-on-surface-variant">{note || '值支持 true、false、数字、JSON 对象或字符串；按 Pi 的原生 compat 配置保存。'}</p>}
  </section>;
}
