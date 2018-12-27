using System;
using System.IO;
using System.Linq;
using System.Net.Sockets;
using System.Reflection;
using System.Reflection.Emit;
using System.Text;
using System.Threading;
using UnityEditor;
using UnityEditor.Callbacks;

namespace EmmyLua
{
	enum TypeKind
	{
		Class,
		Array,
	}

	enum Proto
	{
		Lib,
		Ping
	}

	[InitializeOnLoad]
	class EmmyLuaService
	{
		private static Socket socket;

		private static Thread reciveThread;

		private static int PORT = 9988;

		private static bool doTryLater;
		
		private static DateTime lastTime;

		private static bool connected;

		static EmmyLuaService()
		{
			EditorApplication.update += Update;
			BeginConnect();
		}

		static void BeginConnect()
		{
			doTryLater = false;
			connected = false;
			try
			{
				if (socket != null)
					socket.Close();
				socket = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
				socket.BeginConnect("127.0.0.1", PORT, OnConnect, socket);
			}
			catch (Exception e)
			{
				TryLater();
			}
		}

		private static void OnConnect(IAsyncResult ar)
		{
			try
			{
				socket.EndConnect(ar);
				connected = true;
				SendData(socket);
			}
			catch (Exception e)
			{
				TryLater();
			}
		}

		private static void TryLater()
		{
			connected = false;
			doTryLater = true;
			lastTime = DateTime.Now;
		}

		private static void Update()
		{
			var sp = DateTime.Now - lastTime;
			if (sp.TotalSeconds > 5)
			{
				if (connected)
				{
					Ping();
				}
				else if (doTryLater)
				{
					BeginConnect();
				}
			}
		}

		[DidReloadScripts]
		static void UpdateScripts()
		{
			BeginConnect();
		}

		private static void WriteString(BinaryWriter writer, string value)
		{
			var encoding = Encoding.UTF8;
			var chars = encoding.GetBytes(value);
			writer.Write(chars.Length);
			writer.Write(chars);
		}

		private static void WriteType(BinaryWriter write, Type type)
		{
			if (type.IsArray)
			{
				write.Write((byte) TypeKind.Array);
				WriteType(write, type.GetElementType());
			}
			else
			{
				write.Write((byte) TypeKind.Class);
				WriteString(write, type.FullName ?? "any");
			}
		}

		private static void Ping()
		{
			using (var buf = new MemoryStream())
			{
				var writer = new BinaryWriter(buf);
				writer.Write(8);
				writer.Write((int) Proto.Ping);
				try
				{
					var bytes = buf.GetBuffer();
					socket.Send(bytes, 8, SocketFlags.None);
				}
				catch (Exception e)
				{
					TryLater();
				}
			}
		}
		
		private static void SendData(Socket client)
		{
			var buf = new MemoryStream();
			var writer = new BinaryWriter(buf);
			writer.Seek(8, SeekOrigin.Begin);
			var types = GetTypes();
			foreach (var type in types)
			{
				var fullName = type.FullName;
				if (!string.IsNullOrEmpty(fullName))
				{
					// full name
					WriteString(writer, fullName);
					
					// base type full name
					{
						string baseTypeFullName = null;
						if (type.BaseType != null)
							baseTypeFullName = type.BaseType.FullName;
						writer.Write(baseTypeFullName != null);
						if (baseTypeFullName != null)
							WriteString(writer, baseTypeFullName);
					}

					// fields
					var fields =
						type.GetFields(BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly);
					writer.Write(fields.Length);
					foreach (var fi in fields)
					{
						WriteString(writer, fi.Name);
						WriteType(writer, fi.FieldType);
					}

					// properties
					var properties =
						type.GetProperties(BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly);
					writer.Write(properties.Length);
					foreach (var pi in properties)
					{
						WriteString(writer, pi.Name);
						WriteType(writer, pi.PropertyType);
					}

					// methods
					var methods =
						(from mi in type.GetMethods(BindingFlags.Public | BindingFlags.Instance |
						                            BindingFlags.DeclaredOnly)
							where !mi.Name.StartsWith("get_") && !mi.Name.StartsWith("set_")
							select mi).ToArray();

					writer.Write(methods.Count());
					foreach (var mi in methods)
					{
						// name
						WriteString(writer, mi.Name);

						// parameters
						var parameterInfos = mi.GetParameters();
						writer.Write(parameterInfos.Length);
						foreach (var pi in parameterInfos)
						{
							WriteString(writer, pi.Name);
							WriteType(writer, pi.ParameterType);
						}

						// returns
						WriteType(writer, mi.ReturnType);
					}
				}
			}

			writer.Flush();
			// write size and proto
			var len = (int) buf.Length;
			writer.Seek(0, SeekOrigin.Begin);
			writer.Write(len);
			writer.Write((int) Proto.Lib);
			writer.Flush();
			// send
			client.Send(buf.GetBuffer(), len, SocketFlags.None);
			writer.Close();
		}

		private static Type[] GetTypes()
		{
			var unityTypes = from assembly in AppDomain.CurrentDomain.GetAssemblies()
				where !(assembly.ManifestModule is ModuleBuilder)
				from type in assembly.GetExportedTypes()
				where type.BaseType != typeof(MulticastDelegate) 
				      && !type.IsInterface 
				      && !type.IsEnum
				      && !IsExcluded(type)
				      //&& type.Namespace != null 
				      //&& type.Namespace.StartsWith("UnityEngine") 
				select type;
			var arr = unityTypes.ToArray();

			return arr;
		}

		private static bool IsExcluded(Type type)
		{
			return false;
		}
	}
}