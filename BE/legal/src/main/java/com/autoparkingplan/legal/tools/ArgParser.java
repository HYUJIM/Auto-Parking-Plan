package com.autoparkingplan.legal.tools;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 파이썬 argparse 의 쓰는 부분만 옮긴 명령줄 해석기 (긴 옵션, {@code --opt=값}, 유일한 앞부분 줄임, nargs 0/1/*, -h).
 * 오류면 사용법과 함께 표준 오류로 알리고 종료 코드 2 (argparse 와 같음).
 */
final class ArgParser {
    /** 옵션 하나. nargs: 0 = 플래그, 1 = 값 하나, -1 = 0개 이상('*'). */
    record Option(String name, int nargs, String metavar, String help, Object dflt) {
    }

    /** 해석 실패 또는 -h (종료 코드 포함). */
    static final class Exit extends Exception {
        final int code;

        Exit(int code) {
            super(null, null, false, false);
            this.code = code;
        }
    }

    private final String prog;
    private final String description;
    private final Map<String, Option> options = new LinkedHashMap<>();

    ArgParser(String prog, String description) {
        this.prog = prog;
        this.description = description;
    }

    ArgParser add(String name, int nargs, String metavar, String help, Object dflt) {
        options.put(name, new Option(name, nargs, metavar, help, dflt));
        return this;
    }

    String usage() {
        StringBuilder sb = new StringBuilder("usage: " + prog + " [-h]");
        for (Option o : options.values()) {
            switch (o.nargs) {
                case 0 -> sb.append(" [").append(o.name).append(']');
                case 1 -> sb.append(" [").append(o.name).append(' ').append(o.metavar).append(']');
                default -> sb.append(" [").append(o.name).append(" [").append(o.metavar).append(" ...]]");
            }
        }
        return sb.toString();
    }

    private void help(PrintStream out) {
        out.println(usage());
        out.println();
        if (description != null) {
            out.println(description);
            out.println();
        }
        out.println("options:");
        out.println("  -h, --help            show this help message and exit");
        for (Option o : options.values()) {
            String head = switch (o.nargs) {
                case 0 -> o.name;
                case 1 -> o.name + " " + o.metavar;
                default -> o.name + " [" + o.metavar + " ...]";
            };
            out.println("  " + head);
            out.println("                        " + o.help);
        }
    }

    private Exit error(PrintStream err, String message) {
        err.println(usage());
        err.println(prog + ": error: " + message);
        return new Exit(2);
    }

    private Option lookup(String token, PrintStream err) throws Exit {
        Option exact = options.get(token);
        if (exact != null) {
            return exact;
        }
        List<Option> cands = new ArrayList<>();
        for (Option o : options.values()) {
            if (o.name.startsWith(token)) {
                cands.add(o);
            }
        }
        if (cands.size() == 1) {
            return cands.getFirst();
        }
        if (cands.size() > 1) {
            List<String> names = cands.stream().map(Option::name).toList();
            throw error(err, "ambiguous option: " + token + " could match " + String.join(", ", names));
        }
        return null;
    }

    /** 해석 결과: 옵션 이름 → 값 (플래그는 Boolean, 값 하나는 String, '*' 는 List&lt;String&gt; 또는 null). */
    Map<String, Object> parse(String[] args, PrintStream out, PrintStream err) throws Exit {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Option o : options.values()) {
            values.put(o.name, o.dflt);
        }
        List<String> unknown = new ArrayList<>();
        int i = 0;
        while (i < args.length) {
            String a = args[i];
            if (a.equals("-h") || a.equals("--help")) {
                help(out);
                throw new Exit(0);
            }
            if (!a.startsWith("--") || a.equals("--")) {
                unknown.add(a);
                i++;
                continue;
            }
            String name = a;
            String inline = null;
            int eq = a.indexOf('=');
            if (eq > 0) {
                name = a.substring(0, eq);
                inline = a.substring(eq + 1);
            }
            Option o = lookup(name, err);
            if (o == null) {
                unknown.add(a);
                i++;
                continue;
            }
            i++;
            if (o.nargs == 0) {
                if (inline != null) {
                    throw error(err, "argument " + o.name + ": ignored explicit argument '" + inline + "'");
                }
                values.put(o.name, Boolean.TRUE);
            } else if (o.nargs == 1) {
                if (inline != null) {
                    values.put(o.name, inline);
                } else if (i < args.length && !(args[i].startsWith("-") && args[i].length() > 1)) {
                    values.put(o.name, args[i++]);
                } else {
                    throw error(err, "argument " + o.name + ": expected one argument");
                }
            } else {
                List<String> list = new ArrayList<>();
                if (inline != null) {
                    list.add(inline);
                }
                while (inline == null && i < args.length && !(args[i].startsWith("-") && args[i].length() > 1)) {
                    list.add(args[i++]);
                }
                values.put(o.name, list);
            }
        }
        if (!unknown.isEmpty()) {
            throw error(err, "unrecognized arguments: " + String.join(" ", unknown));
        }
        return values;
    }
}
