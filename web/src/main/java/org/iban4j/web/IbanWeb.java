package org.iban4j.web;

import java.util.ArrayList;
import java.util.List;
import org.iban4j.Bic;
import org.iban4j.BicFormatException;
import org.iban4j.BicUtil;
import org.iban4j.CountryCode;
import org.iban4j.Iban;
import org.iban4j.Iban4jException;
import org.iban4j.IbanFormatException;
import org.iban4j.IbanUtil;
import org.iban4j.InvalidCheckDigitException;
import org.iban4j.UnsupportedCountryException;
import org.iban4j.bban.BbanEntryType;
import org.iban4j.bban.BbanStructure;
import org.iban4j.bban.BbanStructureEntry;
import org.iban4j.countryrules.CountryRulesRegistry;
import org.teavm.jso.JSExport;

/**
 * Мост между библиотекой iban4j и веб-страницей.
 * TeaVM компилирует этот класс (вместе с библиотекой) в JavaScript;
 * методы с @JSExport становятся функциями, которые вызывает страница.
 * Все функции принимают и возвращают строки (JSON), чтобы интерфейс был простым.
 */
public final class IbanWeb {

  private IbanWeb() {
  }

  /** Список поддерживаемых стран и полей, которые для них нужны. */
  @JSExport
  public static String countries() {
    List<CountryCode> list = new ArrayList<>(BbanStructure.supportedCountries());
    list.sort((a, b) -> a.getName().compareTo(b.getName()));
    StringBuilder sb = new StringBuilder("[");
    boolean first = true;
    for (CountryCode cc : list) {
      BbanStructure structure = BbanStructure.forCountry(cc);
      if (structure == null) {
        continue;
      }
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append("{\"code\":").append(q(cc.getAlpha2()))
          .append(",\"name\":").append(q(cc.getName()))
          .append(",\"canCompute\":").append(CountryRulesRegistry.get(cc) != null)
          .append(",\"length\":").append(structure.getBbanLength() + 4)
          .append(",\"fields\":[");
      boolean f = true;
      for (BbanStructureEntry e : structure.getEntries()) {
        if (!f) {
          sb.append(',');
        }
        f = false;
        sb.append("{\"type\":").append(q(e.getEntryType().name()))
            .append(",\"label\":").append(q(fieldName(e.getEntryType())))
            .append(",\"length\":").append(e.getLength())
            .append(",\"charType\":").append(q(e.getCharacterType().name()))
            .append(",\"hint\":").append(q(charTypeName(e.getCharacterType())))
            .append('}');
      }
      sb.append("]}");
    }
    return sb.append(']').toString();
  }

  /**
   * Собрать IBAN из данных. Пустое поле — случайное значение;
   * пустая национальная контрольная цифра — вычисляется автоматически (если для страны есть правило).
   */
  @JSExport
  public static String build(String country, String bankCode, String bankCodeExt, String branchCode,
      String accountNumber, String nationalCheckDigit, String accountType, String ownerAccountType,
      String identificationNumber) {
    try {
      CountryCode cc = country(country);
      if (cc == null) {
        return error("Выберите страну из списка.");
      }
      BbanStructure structure = BbanStructure.forCountry(cc);
      Iban.Builder b = new Iban.Builder().countryCode(cc).leftPadding(false);
      List<String> notes = new ArrayList<>();
      BbanStructureEntry checkEntry = null;
      String checkValue = null;

      for (BbanStructureEntry e : structure.getEntries()) {
        String raw = valueFor(e.getEntryType(), bankCode, bankCodeExt, branchCode, accountNumber,
            nationalCheckDigit, accountType, ownerAccountType, identificationNumber);
        String v = clean(raw);
        if (e.getEntryType() == BbanEntryType.national_check_digit) {
          checkEntry = e;
          checkValue = v;
          continue;
        }
        if (v.isEmpty()) {
          v = e.getRandom();
          notes.add(fieldName(e.getEntryType()) + ": подставлено случайное значение " + v);
        } else {
          String padded = pad(e, v);
          if (!padded.equals(v)) {
            notes.add(fieldName(e.getEntryType()) + ": дополнено нулями слева до " + padded);
            v = padded;
          }
          String problem = checkField(e, v);
          if (problem != null) {
            return error(problem);
          }
        }
        set(b, e.getEntryType(), v);
      }

      Iban result;
      if (checkEntry != null) {
        if (checkValue.isEmpty()) {
          if (CountryRulesRegistry.get(cc) != null) {
            result = findNationalCheckDigit(b, checkEntry);
            if (result == null) {
              return error("Не удалось подобрать национальную контрольную цифру для этих данных.");
            }
            notes.add(fieldName(BbanEntryType.national_check_digit) + ": вычислена автоматически — "
                + result.getNationalCheckDigit());
          } else {
            String r = checkEntry.getRandom();
            notes.add(fieldName(BbanEntryType.national_check_digit) + ": подставлено случайное значение "
                + r + " (для этой страны правило расчёта в библиотеке нет)");
            result = b.nationalCheckDigit(r).build();
          }
        } else {
          String problem = checkField(checkEntry, checkValue);
          if (problem != null) {
            return error(problem);
          }
          result = b.nationalCheckDigit(checkValue).build();
        }
      } else {
        result = b.build();
      }
      return ibanJson(result, notes);
    } catch (Iban4jException | IllegalArgumentException e) {
      return error("Библиотека отклонила данные: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("Непредвиденная ошибка: " + e);
    }
  }

  /** Случайный корректный IBAN для страны. */
  @JSExport
  public static String random(String country) {
    try {
      CountryCode cc = country(country);
      if (cc == null) {
        return error("Выберите страну из списка.");
      }
      Iban r = Iban.random(cc);
      for (int i = 0; i < 1000 && !IbanUtil.isValidWithCountryRules(r.toString()); i++) {
        r = Iban.random(cc);
      }
      List<String> notes = new ArrayList<>();
      notes.add("Номер придуман случайно: он правильный по форме, но такого счёта не существует.");
      return ibanJson(r, notes);
    } catch (RuntimeException e) {
      return error("Непредвиденная ошибка: " + e);
    }
  }

  /** Проверить IBAN. */
  @JSExport
  public static String check(String input) {
    String s = clean(input);
    if (s.isEmpty()) {
      return error("Введите IBAN.");
    }
    try {
      IbanUtil.validate(s);
      return ibanJson(Iban.valueOf(s), new ArrayList<>());
    } catch (InvalidCheckDigitException e) {
      return error("Неверная контрольная сумма — в номере опечатка (или номер выдуман).");
    } catch (UnsupportedCountryException e) {
      return error("Страна не поддерживается или код страны (первые 2 буквы) неверный.");
    } catch (IbanFormatException e) {
      return error(formatProblem(e, s));
    } catch (RuntimeException e) {
      return error("Непредвиденная ошибка: " + e);
    }
  }

  /** Проверить BIC (SWIFT-код банка). */
  @JSExport
  public static String checkBic(String input) {
    String s = clean(input);
    if (s.isEmpty()) {
      return error("Введите BIC.");
    }
    try {
      BicUtil.validate(s);
      Bic bic = Bic.valueOf(s);
      StringBuilder sb = new StringBuilder("{\"ok\":true,\"bic\":").append(q(bic.toString()))
          .append(",\"parts\":[");
      part(sb, "Код банка", bic.getBankCode(), true);
      part(sb, "Страна", bic.getCountryCode().getAlpha2() + " — " + bic.getCountryCode().getName(), false);
      part(sb, "Код города", bic.getLocationCode(), false);
      part(sb, "Отделение", bic.getBranchCode() == null ? "головной офис (XXX)" : bic.getBranchCode(), false);
      return sb.append("]}").toString();
    } catch (BicFormatException e) {
      if (s.length() != 8 && s.length() != 11) {
        return error("BIC должен состоять из 8 или 11 символов, а введено " + s.length() + ".");
      }
      return error("BIC неверный: " + e.getMessage());
    } catch (UnsupportedCountryException e) {
      return error("BIC неверный: неизвестный код страны (символы 5–6).");
    } catch (RuntimeException e) {
      return error("Непредвиденная ошибка: " + e);
    }
  }

  // ---------------- вспомогательное ----------------

  static Iban findNationalCheckDigit(Iban.Builder b, BbanStructureEntry entry) {
    for (String candidate : entry.enumerateValues()) {
      try {
        Iban iban = b.nationalCheckDigit(candidate).build(true);
        if (IbanUtil.isValidWithCountryRules(iban.toString())) {
          return iban;
        }
      } catch (Iban4jException ignored) {
        // не подошла — следующая
      }
    }
    return null;
  }

  static String ibanJson(Iban iban, List<String> notes) {
    String value = iban.toString();
    boolean hasRule = CountryRulesRegistry.get(iban.getCountryCode()) != null;
    boolean nationalOk = IbanUtil.isValidWithCountryRules(value);
    StringBuilder sb = new StringBuilder("{\"ok\":true")
        .append(",\"iban\":").append(q(value))
        .append(",\"formatted\":").append(q(iban.toFormattedString()))
        .append(",\"nationalRule\":").append(hasRule)
        .append(",\"nationalOk\":").append(nationalOk)
        .append(",\"parts\":[");
    part(sb, "Страна", iban.getCountryCode().getAlpha2() + " — " + iban.getCountryCode().getName(), true);
    part(sb, "Контрольные цифры IBAN", iban.getCheckDigit(), false);
    BbanStructure structure = BbanStructure.forCountry(iban.getCountryCode());
    for (BbanStructureEntry e : structure.getEntries()) {
      part(sb, fieldName(e.getEntryType()), get(iban, e.getEntryType()), false);
    }
    sb.append("],\"notes\":[");
    for (int i = 0; i < notes.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(q(notes.get(i)));
    }
    return sb.append("]}").toString();
  }

  static void part(StringBuilder sb, String label, String value, boolean first) {
    if (!first) {
      sb.append(',');
    }
    sb.append("{\"label\":").append(q(label)).append(",\"value\":").append(q(value)).append('}');
  }

  static String formatProblem(IbanFormatException e, String s) {
    if (s.length() < 4) {
      return "Слишком короткий номер.";
    }
    CountryCode cc = CountryCode.getByCode(s.substring(0, 2));
    if (cc == null || BbanStructure.forCountry(cc) == null) {
      return "Неизвестный код страны «" + s.substring(0, 2) + "» (первые 2 буквы IBAN).";
    }
    {
      BbanStructure st = BbanStructure.forCountry(cc);
      if (st != null && s.length() != st.getBbanLength() + 4) {
        return "Неверная длина: для страны " + cc.getName() + " IBAN должен содержать "
            + (st.getBbanLength() + 4) + " символов, а введено " + s.length() + ".";
      }
    }
    return "Неверный формат: " + e.getMessage();
  }

  static String checkField(BbanStructureEntry e, String v) {
    String name = fieldName(e.getEntryType());
    if (v.length() != e.getLength()) {
      return name + ": нужно ровно " + e.getLength() + " симв., а введено " + v.length() + ".";
    }
    for (int i = 0; i < v.length(); i++) {
      char c = v.charAt(i);
      boolean digit = c >= '0' && c <= '9';
      boolean letter = c >= 'A' && c <= 'Z';
      boolean ok;
      switch (e.getCharacterType()) {
        case n:
          ok = digit;
          break;
        case a:
          ok = letter;
          break;
        default:
          ok = digit || letter;
      }
      if (!ok) {
        return name + ": символ «" + c + "» не подходит — допускаются " + charTypeName(e.getCharacterType()) + ".";
      }
    }
    return null;
  }

  static String pad(BbanStructureEntry e, String v) {
    if (e.getCharacterType() == BbanStructureEntry.EntryCharacterType.a || v.length() >= e.getLength()) {
      return v;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = v.length(); i < e.getLength(); i++) {
      sb.append('0');
    }
    return sb.append(v).toString();
  }

  static CountryCode country(String code) {
    String c = clean(code);
    if (c.length() != 2) {
      return null;
    }
    CountryCode cc = CountryCode.getByCode(c);
    return cc != null && BbanStructure.forCountry(cc) != null ? cc : null;
  }

  static String clean(String s) {
    if (s == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c != ' ' && c != '\t' && c != '\n' && c != '\r' && c != '-' && c != ' ') {
        sb.append(Character.toUpperCase(c));
      }
    }
    return sb.toString();
  }

  static String valueFor(BbanEntryType t, String bankCode, String bankCodeExt, String branchCode,
      String accountNumber, String nationalCheckDigit, String accountType, String ownerAccountType,
      String identificationNumber) {
    switch (t) {
      case bank_code: return bankCode;
      case bank_code_ext: return bankCodeExt;
      case branch_code: return branchCode;
      case account_number: return accountNumber;
      case national_check_digit: return nationalCheckDigit;
      case account_type: return accountType;
      case owner_account_number: return ownerAccountType;
      case identification_number: return identificationNumber;
      default: return "";
    }
  }

  static void set(Iban.Builder b, BbanEntryType t, String v) {
    switch (t) {
      case bank_code: b.bankCode(v); break;
      case bank_code_ext: b.bankCodeExt(v); break;
      case branch_code: b.branchCode(v); break;
      case account_number: b.accountNumber(v); break;
      case national_check_digit: b.nationalCheckDigit(v); break;
      case account_type: b.accountType(v); break;
      case owner_account_number: b.ownerAccountType(v); break;
      case identification_number: b.identificationNumber(v); break;
      default: break;
    }
  }

  static String get(Iban iban, BbanEntryType t) {
    switch (t) {
      case bank_code: return iban.getBankCode();
      case bank_code_ext: return IbanUtil.getBankCodeExt(iban.toString());
      case branch_code: return iban.getBranchCode();
      case account_number: return iban.getAccountNumber();
      case national_check_digit: return iban.getNationalCheckDigit();
      case account_type: return iban.getAccountType();
      case owner_account_number: return iban.getOwnerAccountType();
      case identification_number: return iban.getIdentificationNumber();
      default: return "";
    }
  }

  static String fieldName(BbanEntryType t) {
    switch (t) {
      case bank_code: return "Код банка";
      case bank_code_ext: return "Доп. код банка";
      case branch_code: return "Код отделения";
      case account_number: return "Номер счёта";
      case national_check_digit: return "Национальная контрольная цифра";
      case account_type: return "Тип счёта";
      case owner_account_number: return "Тип владельца счёта";
      case identification_number: return "Идентификационный номер";
      default: return t.name();
    }
  }

  static String charTypeName(BbanStructureEntry.EntryCharacterType t) {
    switch (t) {
      case n: return "только цифры";
      case a: return "только латинские буквы";
      default: return "латинские буквы и цифры";
    }
  }

  static String error(String message) {
    return "{\"ok\":false,\"error\":" + q(message) + "}";
  }

  static String q(String s) {
    if (s == null) {
      return "null";
    }
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default:
          if (c < 0x20) {
            String h = Integer.toHexString(c);
            sb.append("\\u");
            for (int k = h.length(); k < 4; k++) {
              sb.append('0');
            }
            sb.append(h);
          } else {
            sb.append(c);
          }
      }
    }
    return sb.append('"').toString();
  }
}
